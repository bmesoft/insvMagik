package eu.bmesoft.insvmagik.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.bmesoft.insvmagik.BuildConfig
import eu.bmesoft.insvmagik.FileProbe
import eu.bmesoft.insvmagik.Probe
import eu.bmesoft.insvmagik.insv.LensCalibration
import eu.bmesoft.insvmagik.media.FrameGrabber
import eu.bmesoft.insvmagik.media.Track
import eu.bmesoft.insvmagik.view.CameraRig
import eu.bmesoft.insvmagik.view.LensImage
import eu.bmesoft.insvmagik.view.Reframer
import eu.bmesoft.insvmagik.view.Rig
import eu.bmesoft.insvmagik.view.SeamCheck
import eu.bmesoft.insvmagik.view.ViewParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Stage 1: one frame of each lens, rendered as a flat view (or the whole sphere) on the CPU. */
class FrameViewModel(app: Application) : AndroidViewModel(app) {
    data class State(
        val files: List<FileProbe> = emptyList(),
        /** The lens streams, lens 0 first. */
        val tracks: List<Track> = emptyList(),
        val calibrations: List<LensCalibration> = emptyList(),
        val timeUs: Long = 0,
        val grabbed: List<FrameGrabber.Grabbed> = emptyList(),
        val grabMs: Long = 0,
        val view: ViewParams = ViewParams(),
        val tweak: CameraRig.Tweak = CameraRig.Tweak(),
        val bitmap: Bitmap? = null,
        val renderMs: Long = 0,
        val seam: SeamCheck.Result? = null,
        val busy: String? = null,
        val error: String? = null,
    ) {
        val durationUs: Long get() = tracks.maxOfOrNull { it.durationUs } ?: 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the renderer needs; a new value re-renders, intermediate ones are skipped. */
    private class Job(val rig: Rig, val images: List<LensImage?>, val view: ViewParams, val seam: Boolean)

    private val jobs = MutableStateFlow<Job?>(null)
    private var rig: Rig? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            jobs.filterNotNull().conflate().collect { job ->
                val t0 = SystemClock.elapsedRealtime()
                val (w, h) = if (job.view.projection == ViewParams.Projection.SPHERE) 1024 to 512 else 960 to 540
                val px = Reframer.render(job.rig, job.images, job.view, w, h)
                val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val ms = SystemClock.elapsedRealtime() - t0
                val seam = if (job.seam) SeamCheck.run(job.rig, job.images) else _state.value.seam
                _state.update { it.copy(bitmap = bmp, renderMs = ms, seam = seam) }
            }
        }
    }

    /** The files picked on the probe tab; grabs the middle of the clip when they change. */
    fun setFiles(files: List<FileProbe>) {
        if (files == _state.value.files) return
        val tracks = Probe.videoStreams(files)
        val cals = files.flatMap { f -> f.calibrations.map { it.second } }
        rig = null
        _state.value = State(files = files, tracks = tracks, calibrations = cals)
        if (tracks.isNotEmpty()) grab(tracks.maxOf { it.durationUs } / 2)
    }

    fun grab(timeUs: Long) {
        val s = _state.value
        if (s.busy != null || s.tracks.isEmpty()) return
        // The last frame starts a frame before the end.
        val step = (1_000_000 / (s.tracks.first().fps.takeIf { it > 0 } ?: 30.0)).toLong()
        val t = timeUs.coerceIn(0, (s.durationUs - step).coerceAtLeast(0))
        _state.update { it.copy(busy = "Decoding the frame at ${secs(t)} s…", error = null, timeUs = t) }
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            runCatching { FrameGrabber.grab(getApplication(), s.tracks, t) }
                .onSuccess { g ->
                    _state.update { it.copy(grabbed = g, grabMs = SystemClock.elapsedRealtime() - t0, busy = null) }
                    rebuild()
                }
                .onFailure { e -> _state.update { it.copy(busy = null, error = "Decoding failed: ${e.message}") } }
        }
    }

    fun setView(view: ViewParams) {
        _state.update { it.copy(view = view) }
        submit(seam = false)
    }

    fun setTweak(tweak: CameraRig.Tweak) {
        _state.update { it.copy(tweak = tweak) }
        rebuild()
    }

    private fun rebuild() {
        val s = _state.value
        val first = s.grabbed.firstOrNull() ?: return
        rig = CameraRig.from(s.calibrations, first.image.width, s.tweak)
        if (rig == null) {
            _state.update { it.copy(error = "No lens calibration of the XI shape in the picked files, so no view yet.") }
            return
        }
        submit(seam = true)
    }

    private fun submit(seam: Boolean) {
        val r = rig ?: return
        val s = _state.value
        val images = List(r.lenses.size) { s.grabbed.getOrNull(it)?.image }
        jobs.value = Job(r, images, s.view, seam || jobs.value == null)
    }

    /** Plain text for the Copy button: what's on screen, to paste into a chat. */
    fun report(): String {
        val s = _state.value
        return buildString {
            append("insvMagik ${BuildConfig.VERSION_NAME} · stage 1 frame\n")
            s.files.forEach { append("${it.name}\n") }
            append("time ${secs(s.timeUs)} s; ")
            s.grabbed.forEachIndexed { i, g -> append("lens $i ${g.fullSize}px → ${g.image.width}px at ${secs(g.ptsUs)} s (${g.decoder}); ") }
            append("decode ${s.grabMs} ms\n")
            val v = s.view
            append("view ${v.projection} yaw ${n(v.yaw)} pitch ${n(v.pitch)} roll ${n(v.roll)} fov ${n(v.fov)} blend ${n(v.blendDeg)}\n")
            append("lens 1 tweak yaw ${n(s.tweak.yaw)} pitch ${n(s.tweak.pitch)} roll ${n(s.tweak.roll)}\n")
            append("render ${s.renderMs} ms; seam match ${s.seam?.let { String.format(Locale.US, "%.3f over %d directions", it.correlation, it.samples) } ?: "–"}\n")
            s.error?.let { append("! $it\n") }
        }
    }

    private fun secs(us: Long) = String.format(Locale.US, "%.2f", us / 1e6)
    private fun n(v: Double) = String.format(Locale.US, "%.1f", v)
}
