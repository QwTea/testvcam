package com.example.virtualcam.xposed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Helper store for diagnostics shared between hooks and UI
data class DiagnosticsSnapshot(
    val activePath: String = "Idle",
    val previewSize: String = "-",
    val frameFormat: String = "-",
    val requestedFps: Float = 0f,
    val actualFps: Float = 0f
)

object DiagnosticsState {
    private val _state = MutableStateFlow(DiagnosticsSnapshot())
    val state: StateFlow<DiagnosticsSnapshot> = _state

    fun update(transform: (DiagnosticsSnapshot) -> DiagnosticsSnapshot) {
        _state.value = transform(_state.value)
    }

    fun set(snapshot: DiagnosticsSnapshot) {
        _state.value = snapshot
    }
}
