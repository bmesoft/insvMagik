package eu.bmesoft.insvmagik

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import eu.bmesoft.insvmagik.insv.CameraInfo
import eu.bmesoft.insvmagik.insv.ChannelSource
import eu.bmesoft.insvmagik.insv.ClipFiles
import eu.bmesoft.insvmagik.insv.Exposures
import eu.bmesoft.insvmagik.insv.ImuSamples
import eu.bmesoft.insvmagik.insv.InsvTrailer
import eu.bmesoft.insvmagik.insv.LensCalibration
import eu.bmesoft.insvmagik.media.BenchResult
import eu.bmesoft.insvmagik.media.MediaProbe
import eu.bmesoft.insvmagik.media.Track
import java.io.FileInputStream
import java.util.Locale

/** Everything stage 0 learns about one picked file. */
class FileProbe(
    val name: String,
    val sizeBytes: Long,
    val tracks: List<Track>,
    val trailer: InsvTrailer?,
    val camera: CameraInfo?,
    val calibrations: List<Pair<String, LensCalibration>>,
    val imu: ImuSamples?,
    val exposures: Exposures?,
    val errors: List<String>,
)

object Probe {
    fun file(context: Context, uri: Uri): FileProbe {
        val (name, size) = nameAndSize(context, uri)
        val errors = mutableListOf<String>()
        val tracks = runCatching { MediaProbe.tracks(context, uri, name) }
            .onFailure { errors += "Android can't read it as a video: ${it.message}" }
            .getOrDefault(emptyList())
        var trailer: InsvTrailer? = null
        var camera: CameraInfo? = null
        var imu: ImuSamples? = null
        var exposures: Exposures? = null
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val src = ChannelSource(ch)
                    trailer = InsvTrailer.read(src)?.also { tr ->
                        camera = tr.bytes(src, InsvTrailer.CAMERA_INFO)?.let(CameraInfo::parse)
                        imu = tr.bytes(src, InsvTrailer.IMU)?.let(ImuSamples::parse)
                        exposures = tr.bytes(src, InsvTrailer.EXPOSURE)?.let(Exposures::parse)
                    }
                }
            }
        }.onFailure { errors += "Reading the Insta360 data failed: ${it.message}" }
        val calibrations = camera?.calibrations.orEmpty()
            .distinctBy { it.second }
            .mapNotNull { (path, s) -> LensCalibration.parse(s)?.let { path to it } }
        return FileProbe(name, size, tracks, trailer, camera, calibrations, imu, exposures, errors)
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: uri.lastPathSegment.orEmpty()) to (if (c.isNull(1)) -1L else c.getLong(1))
        }
        return uri.lastPathSegment.orEmpty() to -1L
    }

    /** The video streams the converter would decode together (two lenses), across all picked files. */
    fun videoStreams(files: List<FileProbe>): List<Track> =
        files.flatMap { f -> f.tracks.filter { it.isVideo } }
}

/** The plain-text report the app shows and copies, made to be pasted into a chat or an issue. */
object ProbeReport {
    fun text(files: List<FileProbe>, bench: List<BenchResult>): String = buildString {
        line("insvMagik ${BuildConfig.VERSION_NAME} · stage 0 probe")
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " (${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL})" else ""
        line("Phone: ${Build.MANUFACTURER} ${Build.MODEL}$soc, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (files.isEmpty()) return@buildString

        for (f in files) {
            line()
            line("${f.name}  ${mb(f.sizeBytes)}")
            for (t in f.tracks) {
                val what = if (t.isVideo) "${t.width}x${t.height} ${n(t.fps, 2)} fps" else ""
                line("  track ${t.index}: ${t.mime} $what ${n(t.durationUs / 1e6, 2)} s")
            }
            f.camera?.let { c -> line("  camera: ${c.model ?: "?"}, firmware ${c.firmware ?: "?"}") }
            val tr = f.trailer
            if (tr == null) {
                line("  no Insta360 data block (not an .insv, or the file is cut short)")
            } else {
                line("  Insta360 data: v${tr.version}, ${mb(tr.size)}, ${tr.layout.name.lowercase()} layout, records " +
                    tr.records.sortedBy { it.id }.joinToString { "0x%x".format(it.id) })
            }
            for ((path, cal) in f.calibrations) {
                line("  calibration field $path: ${cal.kind}, grid ${cal.gridWidth}x${cal.gridHeight}")
                cal.lenses.forEachIndexed { i, l ->
                    val (cx, cy) = cal.localCenter(i)
                    val size = l.radius?.let { "r ${n(it, 1)}" } ?: "xi ${n(l.xi ?: 0.0, 3)} f ${n(l.fx ?: 0.0, 1)}"
                    line("    lens $i: $size, centre ${n(cx, 1)},${n(cy, 1)}, yaw/pitch/roll ${n(l.yaw, 2)}/${n(l.pitch, 2)}/${n(l.roll, 2)}")
                }
            }
            f.imu?.let { line("  gyro: ${it.count} samples, ${it.format}, ${n(it.rateHz, 0)} Hz over ${n(it.spanUs / 1e6, 2)} s") }
            f.exposures?.let { e ->
                val s = e.seconds.filter { it > 0 }
                val range = if (s.isEmpty()) "" else ", 1/${n(1 / s.max(), 0)}..1/${n(1 / s.min(), 0)} s"
                line("  exposure: ${e.count} entries, step ${n(e.frameStepUs / 1000.0, 2)} ms$range")
            }
            f.errors.forEach { line("  ! $it") }
        }

        val notes = ClipFiles.check(files.map { it.name })
        if (notes.isNotEmpty()) {
            line()
            line("Notes")
            notes.forEach { line("- $it") }
        }

        val streams = Probe.videoStreams(files)
        line()
        if (streams.isEmpty()) {
            line("No video stream to decode.")
            return@buildString
        }
        val s0 = streams.first()
        val same = streams.all { it.mime == s0.mime && it.width == s0.width && it.height == s0.height }
        line("Decoding: ${streams.size} stream(s) together, ${s0.mime} ${s0.width}x${s0.height} @ ${n(s0.fps, 2)} fps" +
            if (same) "" else " (streams differ!)")
        val decoders = MediaProbe.decoders(s0.mime, s0.width, s0.height, s0.fps, streams.size)
        for (d in decoders) {
            val perf = when (d.perfOk) { true -> "yes"; false -> "no"; null -> "not declared" }
            line("  ${d.name} ${if (d.hardware) "HW" else "SW"}  max ${d.maxWidth}x${d.maxHeight}  " +
                "size ${yn(d.sizeOk)}  size+rate ${yn(d.rateOk)}  ${streams.size} streams in real time: $perf  instances ${d.maxInstances}")
        }
        line(verdict(decoders.firstOrNull(), streams.size, s0))

        if (bench.isNotEmpty()) {
            line()
            line("Decode speed test (each stream up to 10 s, all decoded together)")
            for (b in bench) {
                val r = "${b.track.fileName} track ${b.track.index}: ${b.frames} frames in ${n(b.wallMs / 1000.0, 1)} s = " +
                    "${n(b.fps, 1)} fps, ${n(b.speed, 2)}x real time, ${b.decoder ?: "no decoder"}"
                line("  $r${b.error?.let { "  ! $it" } ?: ""}")
            }
            val ok = bench.filter { it.error == null && it.frames > 0 }
            if (ok.size == bench.size) {
                val pace = ok.minOf { it.speed }
                line("  Converter pace (slowest stream): ${n(pace, 2)}x real time, so 1 min of video takes about ${n(60 / pace, 0)} s to decode.")
            } else {
                line("  At least one stream failed: this phone can't decode this clip the way the converter needs.")
            }
        }
    }

    private fun verdict(best: eu.bmesoft.insvmagik.media.DecoderFit?, streams: Int, s: Track): String = when {
        best == null -> "Verdict: no decoder for ${s.mime} on this phone."
        !best.sizeOk -> "Verdict: no decoder here takes ${s.width}x${s.height}; full quality won't work on this phone (proxy .lrv only)."
        best.maxInstances < streams -> "Verdict: the decoder allows only ${best.maxInstances} at once, $streams needed."
        best.perfOk == false || (best.perfOk == null && !best.rateOk) ->
            "Verdict: size OK, but declared speed is below $streams stream(s) in real time; expect a slower export. Run the speed test."
        else -> "Verdict: should decode in real time or faster; confirm with the speed test."
    }

    private fun StringBuilder.line(s: String = "") = append(s).append('\n')
    private fun n(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
    private fun yn(b: Boolean) = if (b) "yes" else "no"
    private fun mb(bytes: Long) = if (bytes < 0) "size ?" else String.format(Locale.US, "%.1f MB", bytes / 1e6)
}
