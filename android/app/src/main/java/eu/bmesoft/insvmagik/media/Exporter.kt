package eu.bmesoft.insvmagik.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import eu.bmesoft.insvmagik.view.Rig
import eu.bmesoft.insvmagik.view.ViewParams
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Stage 2: the whole clip through one fixed view into a 1080p H.264 MP4 in Movies/insvMagik
 * (docs/plan.md, "How the converter works"). Each lens: MediaExtractor → hardware decoder →
 * SurfaceTexture; [GlReframer] draws the view onto the encoder's input surface; MediaMuxer writes
 * the file. Frames of the two lenses are paired by timestamp; the source timestamps go through
 * unchanged. The audio track (ONE X2's `_00_` file) is copied, not re-encoded.
 */
object Exporter {
    class Settings(
        val width: Int = 1920,
        val height: Int = 1080,
        val bitrate: Int = 16_000_000,
    )

    class Result(val uri: Uri, val displayName: String, val frames: Int, val mediaUs: Long, val wallMs: Long, val bytes: Long) {
        /** × real time. */
        val speed: Double get() = if (wallMs > 0) mediaUs / 1000.0 / wallMs else 0.0
    }

    /**
     * [tracks]: the lens streams, lens 0 first; [rig] in the tracks' full-size pixels.
     * [onProgress] gets 0..1 from the export thread. Cancelling the coroutine stops the export and
     * deletes the unfinished file.
     */
    suspend fun export(
        context: Context, tracks: List<Track>, audio: Track?, rig: Rig, view: ViewParams, name: String,
        settings: Settings = Settings(), onProgress: (Float) -> Unit,
    ): Result {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "export") }
        try {
            // EGL is bound to a thread: the whole export runs on this one.
            return withContext(executor.asCoroutineDispatcher()) { run(context, tracks, audio, rig, view, name, settings, onProgress) }
        } finally {
            executor.shutdown()
        }
    }

    private suspend fun run(
        context: Context, tracks: List<Track>, audio: Track?, rig: Rig, view: ViewParams, name: String,
        settings: Settings, onProgress: (Float) -> Unit,
    ): Result {
        require(tracks.isNotEmpty() && tracks.size <= 2)
        val resolver = context.contentResolver
        val displayName = "$name.mp4"
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/insvMagik")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        ) ?: error("can't create the output file")

        val fps = tracks[0].fps.takeIf { it > 0 } ?: 30.0
        val duration = tracks.maxOf { it.durationUs }.coerceAtLeast(1)
        val t0 = SystemClock.elapsedRealtime()
        var frames = 0
        var lastPts = 0L
        var finished = false

        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("can't open the output file")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoOut = -1
        var audioOut = -1

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var inputSurface: Surface? = null
        var gl: GlReframer? = null
        val extractors = mutableListOf<MediaExtractor>()
        val decoders = mutableListOf<MediaCodec>()
        val decoderSurfaces = mutableListOf<Surface>()
        try {
            encoder.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, settings.width, settings.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            inputSurface = encoder.createInputSurface()
            encoder.start()
            val g = GlReframer(inputSurface, settings.width, settings.height, tracks.size)
            gl = g

            for ((i, t) in tracks.withIndex()) {
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(context, t.uri, null)
                ex.selectTrack(t.index)
                val dec = MediaCodec.createDecoderByType(t.mime).also { decoders += it }
                dec.configure(ex.getTrackFormat(t.index), Surface(g.surfaceTextures[i]).also { decoderSurfaces += it }, null, 0)
                dec.start()
            }

            val encInfo = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, if (end) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end) return
                            check(SystemClock.elapsedRealtime() < deadline) { "the encoder didn't finish" }
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "the encoder changed format twice" }
                            videoOut = muxer.addTrack(encoder.outputFormat)
                            if (audio != null) audioOut = muxer.addTrack(audio.format)
                            muxer.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = encoder.getOutputBuffer(idx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                            if (encInfo.size > 0 && muxerStarted) muxer.writeSampleData(videoOut, buf, encInfo)
                            encoder.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val n = tracks.size
            val inputDone = BooleanArray(n)
            val eos = BooleanArray(n)
            val ready = BooleanArray(n)
            val pts = LongArray(n)
            val info = MediaCodec.BufferInfo()
            val halfFrame = (500_000 / fps).toLong()
            val sizes = tracks.map { it.width to it.height }
            val present = List(2) { it < n }
            while (true) {
                coroutineContext.ensureActive()
                for (i in 0 until n) {
                    if (inputDone[i]) continue
                    val idx = decoders[i].dequeueInputBuffer(0)
                    if (idx < 0) continue
                    val size = extractors[i].readSampleData(decoders[i].getInputBuffer(idx)!!, 0)
                    if (size < 0) {
                        decoders[i].queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone[i] = true
                    } else {
                        decoders[i].queueInputBuffer(idx, 0, size, extractors[i].sampleTime, 0)
                        extractors[i].advance()
                    }
                }
                for (i in 0 until n) {
                    if (ready[i] || eos[i]) continue
                    val idx = decoders[i].dequeueOutputBuffer(info, 2_000)
                    if (idx < 0) continue
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        decoders[i].releaseOutputBuffer(idx, true)
                        check(g.latch(i)) { "lens $i: no frame from the decoder" }
                        pts[i] = info.presentationTimeUs
                        ready[i] = true
                    } else {
                        decoders[i].releaseOutputBuffer(idx, false)
                    }
                    if (end) eos[i] = true
                }
                if ((0 until n).all { ready[it] }) {
                    // Pair by time: if one lens is ahead, drop the other's older frame.
                    val newest = pts.max()
                    val stale = (0 until n).filter { newest - pts[it] > halfFrame }
                    if (stale.isNotEmpty()) {
                        stale.forEach { ready[it] = false }
                        continue
                    }
                    g.draw(rig, view, sizes, present)
                    g.swap(pts[0])
                    ready.fill(false)
                    frames++
                    lastPts = pts[0]
                    drain(false)
                    onProgress((lastPts.toFloat() / duration).coerceIn(0f, 1f))
                }
                // Done when a lens has no more frames waiting to be used.
                if ((0 until n).any { eos[it] && !ready[it] }) break
            }
            encoder.signalEndOfInputStream()
            drain(true)
            check(muxerStarted && frames > 0) { "no frames were encoded" }

            if (audio != null) copyAudio(context, audio, muxer, audioOut)
            muxer.stop()
            finished = true
        } finally {
            decoders.forEach { runCatching { it.stop() }; runCatching { it.release() } }
            extractors.forEach { it.release() }
            decoderSurfaces.forEach { it.release() }
            runCatching { gl?.close() }
            inputSurface?.release()
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (!finished && muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            pfd.close()
            if (!finished) runCatching { resolver.delete(uri, null, null) }
        }

        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        onProgress(1f)
        return Result(uri, displayName, frames, lastPts, SystemClock.elapsedRealtime() - t0, bytes)
    }

    private fun copyAudio(context: Context, audio: Track, muxer: MediaMuxer, out: Int) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, audio.uri, null)
            ex.selectTrack(audio.index)
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                muxer.writeSampleData(out, buf, info)
                ex.advance()
            }
        } finally {
            ex.release()
        }
    }
}
