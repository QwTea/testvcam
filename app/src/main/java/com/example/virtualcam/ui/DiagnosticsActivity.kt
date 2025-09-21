package com.example.virtualcam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.virtualcam.VirtualCamLogBuffer
import com.example.virtualcam.ui.theme.VirtualCamTheme
import com.example.virtualcam.xposed.DiagnosticsSnapshot
import com.example.virtualcam.xposed.DiagnosticsState
import kotlinx.coroutines.flow.collectLatest

// GREP: UI_DIAG
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VirtualCamTheme {
                val snapshot by DiagnosticsState.state.collectAsState()
                var logs by remember { mutableStateOf(VirtualCamLogBuffer.snapshot()) }
                DisposableEffect(Unit) {
                    val listener: (List<String>) -> Unit = { lines -> logs = lines }
                    VirtualCamLogBuffer.register(listener)
                    onDispose { VirtualCamLogBuffer.unregister(listener) }
                }
                DiagnosticsScreen(snapshot = snapshot, logs = logs.takeLast(20).reversed())
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(snapshot: DiagnosticsSnapshot, logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Диагностика", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Active path: ${snapshot.activePath}")
                Text("Preview size: ${snapshot.previewSize}")
                Text("Frame format: ${snapshot.frameFormat}")
                Text("Requested FPS: ${"%.2f".format(snapshot.requestedFps)}")
                Text("Actual FPS: ${"%.2f".format(snapshot.actualFps)}")
            }
        }
        Text(text = "Последние логи", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(logs) { line ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = line,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
