package eu.bmesoft.insvmagik.media

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class BenchResult(
    val track: Track,
    val decoder: String?,
    val frames: Int,
    /** Video time decoded (last frame's timestamp). */
    val mediaUs: Long,
    val wallMs: Long,
    val error: String?,
) {
    val fps: Double get() = if (wallMs > 0) frames * 1000.0 / wallMs else 0.0

    /** How many times faster than real time; 1.0 = as fast as the video plays. */
    val speed: Double get() = if (wallMs > 0) mediaUs / 1000.0 / wallMs else 0.0
}

/**
 * Decodes video tracks the way the converter will: all at the same time, each by a hardware
 * decoder straight into a GPU-readable surface (an [ImageReader] here, a SurfaceTexture later), with
 * no copy to the CPU. The slowest stream sets the converter's pace; the shader and the 1080p
 * encoder add little on top (docs/plan.md).
 */
object DecodeBench {
    suspend fun run(context: Context, tracks: List<Track>, limitUs: Long = 10_000_000L): List<BenchResult> = coroutineScope {
        tracks.map { t -> async(Dispatchers.IO) { decode(context, t, limitUs) } }.awaitAll()
    }

    private fun decode(context: Context, t: Track, limitUs: Long): BenchResult {
        val thread = HandlerThread("bench-${t.index}").apply { start() }
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        var frames = 0
        var lastPts = 0L
        var t0 = SystemClock.elapsedRealtime()
        try {
            ex.setDataSource(context, t.uri, null)
            ex.selectTrack(t.index)
            val format = ex.getTrackFormat(t.index)
            reader = ImageReader.newInstance(t.width, t.height, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.close() }, Handler(thread.looper))
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(t.mime)
            codec = c
            c.configure(format, reader.surface, null, 0)
            c.start()
            t0 = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = t0 + 120_000
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    return BenchResult(t, c.name, frames, lastPts, SystemClock.elapsedRealtime() - t0, "timed out after 120 s")
                }
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > limitUs) {
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
                    val hasFrame = !eos || info.size > 0
                    if (hasFrame) {
                        frames++
                        lastPts = info.presentationTimeUs
                    }
                    c.releaseOutputBuffer(o, hasFrame)
                    if (eos) break
                }
            }
            return BenchResult(t, c.name, frames, lastPts, SystemClock.elapsedRealtime() - t0, null)
        } catch (e: Exception) {
            return BenchResult(t, codec?.name, frames, lastPts, SystemClock.elapsedRealtime() - t0, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            reader?.close()
            ex.release()
            thread.quitSafely()
        }
    }
}
