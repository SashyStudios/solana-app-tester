package com.clockin.apptester

import com.clockin.apptester.model.RecordedStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecorderMode { IDLE, RECORDING, REPLAYING }

/**
 * Shared state between the AccessibilityService (which Android creates/destroys on
 * its own schedule once the user enables it in Settings) and the Activity UI.
 * v1 = one active recording at a time, held in memory only.
 */
object RecorderBridge {
    private var service: RecordingAccessibilityService? = null

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _mode = MutableStateFlow(RecorderMode.IDLE)
    val mode: StateFlow<RecorderMode> = _mode.asStateFlow()

    private val _recordedSteps = MutableStateFlow<List<RecordedStep>>(emptyList())
    val recordedSteps: StateFlow<List<RecordedStep>> = _recordedSteps.asStateFlow()

    private val _replayLog = MutableStateFlow<List<String>>(emptyList())
    val replayLog: StateFlow<List<String>> = _replayLog.asStateFlow()

    internal fun attachService(instance: RecordingAccessibilityService) {
        service = instance
        _isServiceConnected.value = true
    }

    internal fun detachService(instance: RecordingAccessibilityService) {
        if (service === instance) {
            service = null
            _isServiceConnected.value = false
        }
    }

    internal fun appendStep(step: RecordedStep) {
        if (_mode.value == RecorderMode.RECORDING) {
            _recordedSteps.update { it + step }
        }
    }

    internal fun appendLog(line: String) {
        _replayLog.update { it + line }
    }

    internal fun replayFinished() {
        _mode.value = RecorderMode.IDLE
    }

    fun startRecording() {
        _recordedSteps.value = emptyList()
        _mode.value = RecorderMode.RECORDING
        service?.setTouchExplorationRequested(true)
    }

    fun stopRecording() {
        _mode.value = RecorderMode.IDLE
        service?.setTouchExplorationRequested(false)
    }

    fun startReplay() {
        val steps = _recordedSteps.value
        if (steps.isEmpty()) {
            appendLog("Replay failed: no recorded steps.")
            return
        }
        val svc = service
        if (svc == null) {
            appendLog("Replay failed: accessibility service not connected.")
            return
        }
        _replayLog.value = emptyList()
        _mode.value = RecorderMode.REPLAYING
        svc.replay(steps)
    }
}
