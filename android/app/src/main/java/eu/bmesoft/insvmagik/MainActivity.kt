package eu.bmesoft.insvmagik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bmesoft.insvmagik.ui.FrameScreen
import eu.bmesoft.insvmagik.ui.FrameViewModel
import eu.bmesoft.insvmagik.ui.ProbeScreen
import eu.bmesoft.insvmagik.ui.ProbeViewModel

class MainActivity : ComponentActivity() {
    private val probe: ProbeViewModel by viewModels()
    private val frame: FrameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    val probed by probe.state.collectAsStateWithLifecycle()
                    Column(Modifier.safeDrawingPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("Probe") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("Frame") })
                        }
                        when (tab) {
                            0 -> ProbeScreen(probe)
                            else -> {
                                LaunchedEffect(probed.files) { frame.setFiles(probed.files) }
                                FrameScreen(frame)
                            }
                        }
                    }
                }
            }
        }
    }
}
