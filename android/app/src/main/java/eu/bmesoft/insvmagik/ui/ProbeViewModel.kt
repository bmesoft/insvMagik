package eu.bmesoft.insvmagik.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.bmesoft.insvmagik.FileProbe
import eu.bmesoft.insvmagik.Probe
import eu.bmesoft.insvmagik.ProbeReport
import eu.bmesoft.insvmagik.media.BenchResult
import eu.bmesoft.insvmagik.media.DecodeBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    data class State(
        val files: List<FileProbe> = emptyList(),
        val bench: List<BenchResult> = emptyList(),
        /** What's running, null when idle. */
        val busy: String? = null,
        val report: String = "",
    )

    private val _state = MutableStateFlow(State(report = ProbeReport.text(emptyList(), emptyList())))
    val state: StateFlow<State> = _state.asStateFlow()

    fun pick(uris: List<Uri>) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "Reading ${uris.size} file(s)…") }
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                uris.map { Probe.file(ctx, it) }.sortedBy { it.name }
            }
            val report = withContext(Dispatchers.Default) { ProbeReport.text(files, emptyList()) }
            _state.value = State(files = files, report = report)
        }
    }

    fun runBench() {
        val s = _state.value
        val streams = Probe.videoStreams(s.files)
        if (s.busy != null || streams.isEmpty()) return
        _state.update { it.copy(busy = "Decoding ${streams.size} stream(s) together…") }
        viewModelScope.launch {
            val bench = DecodeBench.run(getApplication(), streams)
            val report = withContext(Dispatchers.Default) { ProbeReport.text(s.files, bench) }
            _state.update { it.copy(bench = bench, busy = null, report = report) }
        }
    }
}
