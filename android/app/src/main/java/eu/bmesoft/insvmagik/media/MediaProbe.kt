package eu.bmesoft.insvmagik.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build

/** One track of a picked file, as Android's MediaExtractor sees it. */
data class Track(
    val uri: Uri,
    val fileName: String,
    val index: Int,
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationUs: Long,
    val format: MediaFormat,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
    val isAudio: Boolean get() = mime.startsWith("audio/")
}

/** What one decoder on this phone says about a video size. */
data class DecoderFit(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val sizeOk: Boolean,
    /** Size and frame rate together, from the decoder's declared limits. */
    val rateOk: Boolean,
    /** Declared performance points cover [streams] streams at this size and rate; null if none declared. */
    val perfOk: Boolean?,
    val maxInstances: Int,
)

object MediaProbe {
    fun tracks(context: Context, uri: Uri, fileName: String): List<Track> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            return (0 until ex.trackCount).map { i ->
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
                Track(
                    uri, fileName, i, mime,
                    f.intOr(MediaFormat.KEY_WIDTH), f.intOr(MediaFormat.KEY_HEIGHT),
                    if (mime.startsWith("video/")) frameRate(ex, i, f) else 0.0,
                    if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                    f,
                )
            }
        } finally {
            ex.release()
        }
    }

    /** The container's frame rate, else measured from the first sample times. */
    private fun frameRate(ex: MediaExtractor, track: Int, f: MediaFormat): Double {
        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { return f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
            runCatching { return f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
        }
        ex.selectTrack(track)
        val times = mutableListOf<Long>()
        while (times.size < 31 && ex.sampleTime >= 0) {
            times += ex.sampleTime
            ex.advance()
        }
        ex.unselectTrack(track)
        if (times.size < 2) return 0.0
        val sorted = times.sorted()
        val steps = sorted.zipWithNext { a, b -> b - a }.sorted()
        val step = steps[steps.size / 2]
        return if (step > 0) 1e6 / step else 0.0
    }

    /** Every decoder for [mime] on this phone, best first (hardware, then those that fit). */
    fun decoders(mime: String, width: Int, height: Int, fps: Double, streams: Int): List<DecoderFit> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        return infos.map { info -> fit(info, mime, width, height, fps, streams) }
            .sortedWith(compareByDescending<DecoderFit> { it.sizeOk }.thenByDescending { it.hardware }.thenByDescending { it.rateOk })
    }

    private fun fit(info: MediaCodecInfo, mime: String, w: Int, h: Int, fps: Double, streams: Int): DecoderFit {
        val caps = info.getCapabilitiesForType(mime)
        val vc = caps.videoCapabilities
        val rate = if (fps > 0) fps else 30.0
        val perf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vc.supportedPerformancePoints?.takeIf { it.isNotEmpty() }?.let { points ->
                // Streams decoded together need their summed frame rate at this size.
                val need = MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, (rate * streams).toInt().coerceAtLeast(1))
                points.any { it.covers(need) }
            }
        } else null
        return DecoderFit(
            name = info.name,
            hardware = info.isHardwareAccelerated,
            maxWidth = vc.supportedWidths.upper,
            maxHeight = vc.supportedHeights.upper,
            sizeOk = vc.isSizeSupported(w, h),
            rateOk = runCatching { vc.areSizeAndRateSupported(w, h, rate) }.getOrDefault(false),
            perfOk = perf,
            maxInstances = caps.maxSupportedInstances,
        )
    }

    private fun MediaFormat.intOr(key: String): Int = if (containsKey(key)) getInteger(key) else 0
}
