package eu.bmesoft.insvmagik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import eu.bmesoft.insvmagik.ui.ProbeScreen
import eu.bmesoft.insvmagik.ui.ProbeViewModel

class MainActivity : ComponentActivity() {
    private val vm: ProbeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ProbeScreen(vm)
                }
            }
        }
    }
}
