package eu.bmesoft.insvmagik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stage 0: pick the clip's .insv file(s), read the report, run the decode test, copy it all. */
@Composable
fun ProbeScreen(vm: ProbeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // .insv has no MIME type Android knows, so the picker must allow every file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.pick(uris)
    }
    val idle = state.busy == null

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Text("insvMagik", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick both files of a clip (…_00_… and …_10_…) or the single file of newer cameras.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = idle) { Text("Choose files") }
            Button(onClick = vm::runBench, enabled = idle && state.files.isNotEmpty()) { Text("Decode test") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.report)) }) { Text("Copy") }
            OutlinedButton(onClick = { reportIssue(context, "Test report: files and decode test", state.report) }) { Text("Report on GitHub") }
        }
        state.busy?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
            Text(state.report, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
