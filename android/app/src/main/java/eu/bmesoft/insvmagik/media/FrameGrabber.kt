package eu.bmesoft.insvmagik.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import eu.bmesoft.insvmagik.view.LensImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil

/**
 * Decodes the frame at one moment of each lens stream into an ARGB picture, shrunk to at most
 * [maxSide] pixels a side for the preview. Uses the hardware decoder in byte-buffer mode and
 * converts YUV on the CPU: slow (a fraction of a second) but simple, and only for stage 1's
 * still frames; the export (stage 2) keeps frames on the GPU.
 */
object FrameGrabber {
    class Grabbed(val image: LensImage, val ptsUs: Long, val decoder: String, val fullSize: Int)

    suspend fun grab(context: Context, tracks: List<Track>, timeUs: Long, maxSide: Int = 1920): List<Grabbed> = coroutineScope {
        tracks.map { t -> async(Dispatchers.IO) { grab(context, t, timeUs, maxSide) } }.awaitAll()
    }

    private fun grab(context: Context, t: Track, timeUs: Long, maxSide: Int): Grabbed {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, t.uri, null)
            ex.selectTrack(t.index)
            val format = ex.getTrackFormat(t.index)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(t.mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Accept the first frame at or after the target, allowing half a frame of rounding.
            val halfFrame = if (t.fps > 0) (500_000 / t.fps).toLong() else 16_000L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            repeat(100_000) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val wanted = info.size > 0 && (info.presentationTimeUs >= timeUs - halfFrame || eos)
                    if (wanted) {
                        val img = c.getOutputImage(o) ?: error("decoder gave no image")
                        val fmt = c.outputFormat
                        val grabbed = img.use { Grabbed(toArgb(it, fmt, maxSide), info.presentationTimeUs, c.name, t.width) }
                        c.releaseOutputBuffer(o, false)
                        return grabbed
                    }
                    c.releaseOutputBuffer(o, false)
                    if (eos) error("no frame at ${timeUs / 1000} ms")
                }
            }
            error("decoding took too long")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /**
     * YUV 4:2:0 → ARGB, keeping every `step`-th pixel. Matrix and range from the decoder's output
     * format; the samples are full-range ("yuvj") BT.709 (A1) and BT.601 (ONE X2).
     */
    private fun toArgb(img: Image, fmt: MediaFormat, maxSide: Int): LensImage {
        val crop = img.cropRect
        val w = crop.width()
        val h = crop.height()
        val step = ceil(maxOf(w, h).toDouble() / maxSide).toInt().coerceAtLeast(1)
        val ow = w / step
        val oh = h / step
        val standard = if (fmt.containsKey(MediaFormat.KEY_COLOR_STANDARD)) fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) else MediaFormat.COLOR_STANDARD_BT709
        val full = !fmt.containsKey(MediaFormat.KEY_COLOR_RANGE) || fmt.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        val bt601 = standard == MediaFormat.COLOR_STANDARD_BT601_PAL || standard == MediaFormat.COLOR_STANDARD_BT601_NTSC
        // R = Y + rv·V, G = Y − gu·U − gv·V, B = Y + bu·U
        val (rv, gu, gv, bu) = if (bt601) listOf(1.402, 0.344136, 0.714136, 1.772) else listOf(1.5748, 0.187324, 0.468124, 1.8556)
        val yScale = if (full) 1.0 else 255.0 / 219.0
        val cScale = if (full) 1.0 else 255.0 / 224.0
        val yOff = if (full) 0.0 else 16.0

        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val y = crop.top + oy * step
            val yRow = y * yP.rowStride
            val uRow = (y / 2) * uP.rowStride
            val vRow = (y / 2) * vP.rowStride
            for (ox in 0 until ow) {
                val x = crop.left + ox * step
                val yy = ((yB.get(yRow + x * yP.pixelStride).toInt() and 0xff) - yOff) * yScale
                val u = ((uB.get(uRow + (x / 2) * uP.pixelStride).toInt() and 0xff) - 128.0) * cScale
                val v = ((vB.get(vRow + (x / 2) * vP.pixelStride).toInt() and 0xff) - 128.0) * cScale
                out[oy * ow + ox] = LensImage.rgb(yy + rv * v, yy - gu * u - gv * v, yy + bu * u)
            }
        }
        return LensImage(ow, oh, out)
    }
}
