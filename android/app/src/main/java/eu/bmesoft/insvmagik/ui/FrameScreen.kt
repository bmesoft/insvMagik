package eu.bmesoft.insvmagik.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import eu.bmesoft.insvmagik.view.CameraRig
import eu.bmesoft.insvmagik.view.ViewParams
import java.util.Locale

/** Stage 1: look around one frame of the clip and check the seam between the lenses. */
@Composable
fun FrameScreen(vm: FrameViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    if (s.tracks.isEmpty()) {
        Text("Pick the clip's files on the Probe tab first.", Modifier.padding(16.dp))
        return
    }
    val v = s.view
    val t = s.tweak
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        val ratio = if (v.projection == ViewParams.Projection.SPHERE) 2f else 16f / 9f
        val bmp = s.bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Rendered view", modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
        } else {
            Spacer(Modifier.fillMaxWidth().aspectRatio(ratio))
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(v.projection == ViewParams.Projection.FLAT, { vm.setView(v.copy(projection = ViewParams.Projection.FLAT)) }, { Text("Flat") })
            FilterChip(v.projection == ViewParams.Projection.SPHERE, { vm.setView(v.copy(projection = ViewParams.Projection.SPHERE)) }, { Text("Sphere") })
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.setView(ViewParams(projection = v.projection, blendDeg = v.blendDeg)) }) { Text("Reset") }
        }
        Angle("Yaw", v.yaw, -180f..180f) { vm.setView(v.copy(yaw = it)) }
        Angle("Pitch", v.pitch, -90f..90f) { vm.setView(v.copy(pitch = it)) }
        Angle("Roll", v.roll, -180f..180f) { vm.setView(v.copy(roll = it)) }
        Angle("Field of view", v.fov, 40f..150f) { vm.setView(v.copy(fov = it)) }

        // Export: the whole clip through this view. Keep the screen on meanwhile.
        val exporting = s.exportProgress != null
        val hostView = LocalView.current
        DisposableEffect(exporting) {
            hostView.keepScreenOn = exporting
            onDispose { hostView.keepScreenOn = false }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (exporting) {
                OutlinedButton(onClick = vm::cancelExport) { Text("Cancel") }
                Text("Exporting ${((s.exportProgress ?: 0f) * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = vm::export, enabled = s.busy == null && s.bitmap != null) { Text("Export this view (1080p)") }
            }
        }
        s.exportProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        s.exportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(8.dp))
        Text("Seam (debug)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Seam match: " + (s.seam?.let { "${fmt(it.correlation, 3)} (higher is better; ~0.76 on the A1 sample)" } ?: "needs both lenses"),
            style = MaterialTheme.typography.bodySmall,
        )
        Angle("Lens 1 yaw", t.yaw, -3f..3f, digits = 2) { vm.setTweak(t.copy(yaw = it)) }
        Angle("Lens 1 pitch", t.pitch, -3f..3f, digits = 2) { vm.setTweak(t.copy(pitch = it)) }
        Angle("Lens 1 roll", t.roll, -3f..3f, digits = 2) { vm.setTweak(t.copy(roll = it)) }
        Angle("Blend width", v.blendDeg, 0.1f..10f) { vm.setView(v.copy(blendDeg = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.setTweak(CameraRig.Tweak()) }) { Text("Reset lens 1") }
            Button(onClick = { clipboard.setText(AnnotatedString(vm.report())) }) { Text("Copy") }
        }
        Text(
            "decode ${s.grabMs} ms · render ${s.renderMs} ms" +
                s.grabbed.joinToString("") { " · ${it.fullSize}→${it.image.width} px" },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
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
