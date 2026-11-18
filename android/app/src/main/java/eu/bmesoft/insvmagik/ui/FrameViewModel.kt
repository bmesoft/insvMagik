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
import eu.bmesoft.insvmagik.view.Aspect
import eu.bmesoft.insvmagik.view.CameraRig
import eu.bmesoft.insvmagik.view.Keyframe
import eu.bmesoft.insvmagik.view.LensImage
import eu.bmesoft.insvmagik.view.Reframer
import eu.bmesoft.insvmagik.view.Rig
import eu.bmesoft.insvmagik.view.SeamCheck
import eu.bmesoft.insvmagik.view.ViewParams
import eu.bmesoft.insvmagik.view.ViewPath
import eu.bmesoft.insvmagik.media.Exporter
import eu.bmesoft.insvmagik.motion.Attitude
import eu.bmesoft.insvmagik.motion.ImuTrack
import eu.bmesoft.insvmagik.motion.SensorAxes
import eu.bmesoft.insvmagik.motion.Stabilize
import eu.bmesoft.insvmagik.motion.Stabilizer
import eu.bmesoft.insvmagik.view.Mat3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One frame of each lens, rendered as a flat view (or the whole sphere) on the CPU (stage 1), the
 * export of the whole clip (stage 2), stabilised by the camera's gyro (stage 4), with the view
 * moving between keyframes and a choice of output shape (stage 5).
 */
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
        /** 0..1 while exporting, null otherwise. */
        val exportProgress: Float? = null,
        val exportResult: String? = null,
        /** How the view follows the camera; only matters when [motionReady]. */
        val stabilize: Stabilize = Stabilize.FOLLOW,
        val motionReady: Boolean = false,
        /** What the gyro data gave, or why there's no stabilisation. */
        val motionNote: String? = null,
        /** Where the view looks at chosen moments; empty = the fixed [view] throughout. */
        val keyframes: List<Keyframe> = emptyList(),
        val aspect: Aspect = Aspect.LANDSCAPE,
    ) {
        val durationUs: Long get() = tracks.maxOfOrNull { it.durationUs } ?: 0

        /** The time of the frame on screen. */
        val frameUs: Long get() = grabbed.firstOrNull()?.ptsUs ?: timeUs

        /** Half a frame: how close a keyframe must be to count as "on this frame". */
        val halfFrameUs: Long get() = (500_000 / (tracks.firstOrNull()?.fps?.takeIf { it > 0 } ?: 30.0)).toLong()

        /** The view on screen: the keyframes' path at this frame, or the fixed view. */
        val shownView: ViewParams get() = ViewPath.at(keyframes, frameUs, view)

        /** The keyframe on the frame on screen, if there is one. */
        val keyframeHere: Keyframe? get() = ViewPath.near(keyframes, frameUs, halfFrameUs)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the renderer needs; a new value re-renders, intermediate ones are skipped. */
    private class RenderJob(
        val rig: Rig, val images: List<LensImage?>, val view: ViewParams, val motion: Mat3, val seam: Boolean,
        val width: Int, val height: Int,
    )

    private val jobs = MutableStateFlow<RenderJob?>(null)
    private var exportJob: Job? = null
    private var rig: Rig? = null
    private var stabilizer: Stabilizer? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            jobs.filterNotNull().conflate().collect { job ->
                val t0 = SystemClock.elapsedRealtime()
                val w = job.width
                val h = job.height
                val px = Reframer.render(job.rig, job.images, job.view, w, h, motion = job.motion)
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
        val (stab, note) = motion(files, tracks.maxOfOrNull { it.durationUs } ?: 0)
        stabilizer = stab
        _state.value = State(files = files, tracks = tracks, calibrations = cals, motionReady = stab != null, motionNote = note)
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

    /**
     * The sliders moved. Without keyframes this is the fixed view; with keyframes it edits the one
     * on this frame, adding it if there is none, so the path goes through what's on screen.
     */
    fun setView(view: ViewParams) {
        _state.update { s ->
            val shown = s.shownView
            val sameLook = view.yaw == shown.yaw && view.pitch == shown.pitch && view.roll == shown.roll && view.fov == shown.fov
            if (s.keyframes.isEmpty() || sameLook) {
                // No keyframes, or only the projection / blend changed: not a keyframe edit.
                s.copy(view = if (s.keyframes.isEmpty()) view else s.view.copy(projection = view.projection, blendDeg = view.blendDeg))
            } else {
                val here = s.keyframeHere
                val key = Keyframe(here?.timeUs ?: s.frameUs, view.yaw, view.pitch, view.roll, view.fov)
                s.copy(
                    view = s.view.copy(projection = view.projection, blendDeg = view.blendDeg),
                    keyframes = (s.keyframes - listOfNotNull(here) + key).sortedBy { it.timeUs },
                )
            }
        }
        submit(seam = false)
    }

    /** Adds a keyframe on this frame from the view on screen, or removes the one that's here. */
    fun toggleKeyframe() {
        _state.update { s ->
            val here = s.keyframeHere
            if (here != null) {
                // The last one gone: keep its view as the fixed one.
                val rest = s.keyframes - here
                s.copy(keyframes = rest, view = if (rest.isEmpty()) s.shownView else s.view)
            } else {
                val v = s.shownView
                s.copy(keyframes = (s.keyframes + Keyframe(s.frameUs, v.yaw, v.pitch, v.roll, v.fov)).sortedBy { it.timeUs })
            }
        }
        submit(seam = false)
    }

    /** Back to one fixed view: the one on screen. */
    fun clearKeyframes() {
        _state.update { it.copy(view = it.shownView, keyframes = emptyList()) }
        submit(seam = false)
    }

    fun setAspect(aspect: Aspect) {
        _state.update { it.copy(aspect = aspect) }
        submit(seam = false)
    }

    fun setStabilize(mode: Stabilize) {
        _state.update { it.copy(stabilize = mode) }
        submit(seam = false)
    }

    /** The stabilisation for the picked files, or why there is none. */
    private fun motion(files: List<FileProbe>, durationUs: Long): Pair<Stabilizer?, String> {
        val f = files.firstOrNull { it.imu != null && it.camera != null }
            ?: return null to "No gyro data in the picked files: no stabilisation."
        val camera = f.camera!!
        val axes = SensorAxes.forModel(camera.model)
            ?: return null to "No stabilisation yet for ${camera.model ?: "this camera"}: its motion sensor's axes aren't known."
        val imu = ImuTrack.from(f.imu!!, camera, axes)
            ?: return null to "The camera info has no first-frame time: can't match the gyro to the video."
        if (!Stabilizer.covers(imu, durationUs)) {
            return null to String.format(
                Locale.US, "The gyro covers %.2f–%.2f s, not the whole video: no stabilisation.",
                imu.timesUs.first() / 1e6, imu.timesUs.last() / 1e6,
            )
        }
        return runCatching {
            val att = Attitude.estimate(imu)
            val bias = att.bias?.let { b ->
                String.format(Locale.US, "; resting gyro offset %.2f °/s taken off", Math.toDegrees(Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])))
            } ?: "; never still, no gyro offset taken off"
            Stabilizer(att) to String.format(
                Locale.US, "Gyro: %d samples from %.2f s to %.2f s%s.",
                imu.count, imu.timesUs.first() / 1e6, imu.timesUs.last() / 1e6, bias,
            )
        }.getOrElse { null to "Gyro data unusable: ${it.message}" }
    }

    private fun motionAt(tUs: Long, s: State): Mat3 =
        stabilizer?.takeIf { s.motionReady }?.correction(tUs, s.stabilize) ?: Mat3.IDENTITY

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
        val v = s.shownView
        val (w, h) = when {
            v.projection == ViewParams.Projection.SPHERE -> 1024 to 512
            else -> s.aspect.width / 2 to s.aspect.height / 2
        }
        jobs.value = RenderJob(r, images, v, motionAt(s.frameUs, s), seam || jobs.value == null, w, h)
    }

    /**
     * The whole clip through the view (fixed or along the keyframes), stabilised as chosen, in the
     * chosen shape, into Movies/insvMagik.
     */
    fun export() {
        val s = _state.value
        if (s.exportProgress != null || s.tracks.isEmpty()) return
        val rig = CameraRig.from(s.calibrations, s.tracks[0].width, s.tweak)
        if (rig == null) {
            _state.update { it.copy(exportResult = "No lens calibration of the XI shape: can't export.") }
            return
        }
        val audio = s.files.flatMap { it.tracks }.firstOrNull { it.isAudio }
        val mode = if (s.motionReady) s.stabilize else Stabilize.OFF
        val name = s.files.first().name.substringBefore(".insv").substringBeforeLast('.') + when (mode) {
            Stabilize.OFF -> "_flat"
            Stabilize.FOLLOW -> "_follow"
            Stabilize.LOCKED -> "_locked"
        } + s.aspect.suffix
        val stab = stabilizer
        val motion: (Long) -> Mat3 = { t -> stab?.correction(t, mode) ?: Mat3.IDENTITY }
        val keys = s.keyframes
        val base = s.view.copy(projection = ViewParams.Projection.FLAT)
        val view: (Long) -> ViewParams = { t -> ViewPath.at(keys, t, base) }
        val settings = Exporter.Settings(s.aspect.width, s.aspect.height)
        _state.update { it.copy(exportProgress = 0f, exportResult = null) }
        exportJob = viewModelScope.launch {
            val text = try {
                var shown = 0
                val r = Exporter.export(getApplication(), s.tracks, audio, rig, view, name, settings, motion = motion) { p ->
                    val permille = (p * 1000).toInt()
                    if (permille - shown >= 5) {
                        shown = permille
                        _state.update { it.copy(exportProgress = p) }
                    }
                }
                String.format(
                    Locale.US, "Saved Movies/insvMagik/%s: %d frames, %.1f s of video in %.1f s = %.2fx real time, %.1f MB%s",
                    r.displayName, r.frames, r.mediaUs / 1e6, r.wallMs / 1e3, r.speed, r.bytes / 1e6,
                    if (audio != null) ", audio copied" else ", no audio in the source",
                )
            } catch (e: CancellationException) {
                "Export cancelled; the unfinished file was deleted."
            } catch (e: Exception) {
                "Export failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            _state.update { it.copy(exportProgress = null, exportResult = text) }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    /** Plain text for the Copy button: what's on screen, to paste into a chat. */
    fun report(): String {
        val s = _state.value
        return buildString {
            append("insvMagik ${BuildConfig.VERSION_NAME} · frame\n")
            s.files.forEach { append("${it.name}\n") }
            append("time ${secs(s.timeUs)} s; ")
            s.grabbed.forEachIndexed { i, g -> append("lens $i ${g.fullSize}px → ${g.image.width}px at ${secs(g.ptsUs)} s (${g.decoder}); ") }
            append("decode ${s.grabMs} ms\n")
            val v = s.shownView
            append("output ${s.aspect.label} ${s.aspect.width}x${s.aspect.height}; keyframes ${s.keyframes.size}")
            s.keyframes.forEach { k -> append(" · ${secs(k.timeUs)} s ${n(k.yaw)}/${n(k.pitch)}/${n(k.roll)} fov ${n(k.fov)}") }
            append("\n")
            append("view ${v.projection} yaw ${n(v.yaw)} pitch ${n(v.pitch)} roll ${n(v.roll)} fov ${n(v.fov)} blend ${n(v.blendDeg)}\n")
            append("stabilise ${if (s.motionReady) s.stabilize else "unavailable"}: ${s.motionNote ?: "–"}\n")
            append("lens 1 tweak yaw ${n(s.tweak.yaw)} pitch ${n(s.tweak.pitch)} roll ${n(s.tweak.roll)}\n")
            append("render ${s.renderMs} ms; seam match ${s.seam?.let { String.format(Locale.US, "%.3f over %d directions", it.correlation, it.samples) } ?: "–"}\n")
            s.error?.let { append("! $it\n") }
            s.exportResult?.let { append("export: $it\n") }
        }
    }

    private fun secs(us: Long) = String.format(Locale.US, "%.2f", us / 1e6)
    private fun n(v: Double) = String.format(Locale.US, "%.1f", v)
}
