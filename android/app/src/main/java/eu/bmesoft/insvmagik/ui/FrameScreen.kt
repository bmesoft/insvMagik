package eu.bmesoft.insvmagik.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bmesoft.insvmagik.motion.Stabilize
import eu.bmesoft.insvmagik.view.Aspect
import eu.bmesoft.insvmagik.view.CameraRig
import eu.bmesoft.insvmagik.view.ViewParams
import java.util.Locale

/**
 * Look around the clip, choose the stabilisation, the output shape and (optionally) keyframes
 * the view moves between, export. The seam tools for checking a new camera fold away.
 */
@Composable
fun FrameScreen(vm: FrameViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    if (s.tracks.isEmpty()) {
        Text("Pick the clip's files on the Files tab first.", Modifier.padding(16.dp))
        return
    }
    val v = s.shownView
    val t = s.tweak
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // The picture, in the output's shape (a tall one doesn't fill the width).
        val sphere = v.projection == ViewParams.Projection.SPHERE
        val ratio = if (sphere) 2f else s.aspect.width.toFloat() / s.aspect.height
        val widthShare = when {
            sphere || s.aspect == Aspect.LANDSCAPE -> 1f
            s.aspect == Aspect.SQUARE -> 0.7f
            else -> 0.5f
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val mod = Modifier.fillMaxWidth(widthShare).aspectRatio(ratio)
            val bmp = s.bitmap
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Rendered view", modifier = mod) else Spacer(mod)
        }
        s.busy?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // Time: pick with the slider, decode on release (a frame takes a moment).
        var time by remember(s.timeUs) { mutableFloatStateOf(s.timeUs / 1e6f) }
        val dur = (s.durationUs / 1e6f).coerceAtLeast(0.01f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time ${fmt(time.toDouble(), 2)} s", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = time.coerceIn(0f, dur), onValueChange = { time = it }, valueRange = 0f..dur,
                onValueChangeFinished = { vm.grab((time * 1e6).toLong()) },
                enabled = s.busy == null, modifier = Modifier.weight(1f),
            )
        }

        // Keyframes: the view at chosen moments, a smooth move in between.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val here = s.keyframeHere != null
            OutlinedButton(onClick = vm::toggleKeyframe, enabled = s.busy == null) {
                Text(if (here) "◆ Remove keyframe" else "◇ Keyframe here")
            }
            if (s.keyframes.isNotEmpty()) TextButton(onClick = vm::clearKeyframes) { Text("Clear all") }
        }
        if (s.keyframes.isEmpty()) {
            Text("No keyframes: one fixed view for the whole clip. Add keyframes at a few moments and the view moves smoothly between them.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.keyframes.forEach { k ->
                    val on = k == s.keyframeHere
                    AssistChip(onClick = { vm.grab(k.timeUs) }, label = { Text((if (on) "◆ " else "◇ ") + fmt(k.timeUs / 1e6, 1) + " s") })
                }
            }
            Text("The sliders set the view at this frame (it becomes a keyframe). Tap a keyframe to go to it.", style = MaterialTheme.typography.bodySmall)
        }

        Angle("Yaw", v.yaw, -180f..180f) { vm.setView(v.copy(yaw = it)) }
        Angle("Pitch", v.pitch, -90f..90f) { vm.setView(v.copy(pitch = it)) }
        Angle("Roll", v.roll, -180f..180f) { vm.setView(v.copy(roll = it)) }
        Angle("Field of view", v.fov, 40f..150f) { vm.setView(v.copy(fov = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.setView(ViewParams(projection = v.projection, blendDeg = v.blendDeg)) }) { Text("Reset view") }
            FilterChip(sphere, { vm.setView(v.copy(projection = if (sphere) ViewParams.Projection.FLAT else ViewParams.Projection.SPHERE)) }, { Text("Whole sphere") })
        }

        // Stabilisation from the gyro: level horizon, the view following the camera or locked.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Motion", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
            for ((mode, label) in listOf(Stabilize.OFF to "Off", Stabilize.FOLLOW to "Follow", Stabilize.LOCKED to "Locked")) {
                FilterChip(
                    selected = if (s.motionReady) s.stabilize == mode else mode == Stabilize.OFF,
                    onClick = { vm.setStabilize(mode) }, label = { Text(label) }, enabled = s.motionReady,
                )
            }
        }
        s.motionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // The output's shape.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shape", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
            for (a in Aspect.entries) FilterChip(s.aspect == a, { vm.setAspect(a) }, { Text(a.label) })
        }

        // Export: the whole clip. Keep the screen on meanwhile.
        val exporting = s.exportProgress != null
        val hostView = LocalView.current
        DisposableEffect(exporting) {
            hostView.keepScreenOn = exporting
            onDispose { hostView.keepScreenOn = false }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (exporting) {
                OutlinedButton(onClick = vm::cancelExport) { Text("Cancel") }
                Text("Exporting ${((s.exportProgress ?: 0f) * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = vm::export, enabled = s.busy == null && s.bitmap != null) {
                    Text("Export ${s.aspect.width}×${s.aspect.height}")
                }
            }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.report())) }) { Text("Copy report") }
        }
        s.exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        s.exportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Seam tools: for checking a new camera's calibration; folded away by default.
        var tools by rememberSaveable { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { tools = !tools }) { Text(if (tools) "▾ Seam tools" else "▸ Seam tools") }
        if (tools) {
            Text(
                "Seam match: " + (s.seam?.let { "${fmt(it.correlation, 3)} (higher is better; ~0.76 on the A1 sample)" } ?: "needs both lenses"),
                style = MaterialTheme.typography.bodySmall,
            )
            Angle("Lens 1 yaw", t.yaw, -3f..3f, digits = 2) { vm.setTweak(t.copy(yaw = it)) }
            Angle("Lens 1 pitch", t.pitch, -3f..3f, digits = 2) { vm.setTweak(t.copy(pitch = it)) }
            Angle("Lens 1 roll", t.roll, -3f..3f, digits = 2) { vm.setTweak(t.copy(roll = it)) }
            Angle("Blend width", v.blendDeg, 0.1f..10f) { vm.setView(v.copy(blendDeg = it)) }
            OutlinedButton(onClick = { vm.setTweak(CameraRig.Tweak()) }) { Text("Reset lens 1") }
            Text(
                "decode ${s.grabMs} ms · render ${s.renderMs} ms" +
                    s.grabbed.joinToString("") { " · ${it.fullSize}→${it.image.width} px" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A labelled slider; follows the finger locally and reports every change. */
@Composable
private fun Angle(label: String, value: Double, range: ClosedFloatingPointRange<Float>, digits: Int = 0, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${fmt(value, digits)}", Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
