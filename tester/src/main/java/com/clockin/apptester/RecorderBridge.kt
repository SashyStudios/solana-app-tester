package com.clockin.apptester

import android.util.Log
import com.clockin.apptester.model.RecordedStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecorderMode { IDLE, COUNTDOWN, RECORDING, REPLAYING }

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

    private val _countdownSecondsRemaining = MutableStateFlow<Int?>(null)
    val countdownSecondsRemaining: StateFlow<Int?> = _countdownSecondsRemaining.asStateFlow()

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
        // Also goes to logcat (tag TesterReplayLog) - the in-app Replay Log list is
        // memory-only and disappears if the process dies, so this is the only way to
        // recover a replay's results after the fact.
        Log.d("TesterReplayLog", line)
    }

    internal fun replayFinished() {
        _mode.value = RecorderMode.IDLE
    }

    /** Called by the service's countdown loop on every tick; null once it ends. */
    internal fun setCountdownSecondsRemaining(seconds: Int?) {
        _countdownSecondsRemaining.value = seconds
    }

    /**
     * Starts the pre-recording countdown. Nothing is recorded while it counts down -
     * the service flips into RecorderMode.RECORDING once it reaches zero (see
     * beginRecording). Touch exploration is never requested anywhere in this path - the
     * Oct 4/5 investigation found Compose doesn't reliably emit TYPE_VIEW_CLICKED for
     * ordinary taps, which touch exploration existed to work around, but classic Android
     * Views (what the demo app is built with now) do emit it on their own.
     */
    fun startRecordingCountdown() {
        if (_mode.value != RecorderMode.IDLE) return
        val svc = service
        if (svc == null) {
            appendLog("Cannot start recording: accessibility service not connected.")
            return
        }
        _recordedSteps.value = emptyList()
        svc.clearLabelCache()
        _mode.value = RecorderMode.COUNTDOWN
        svc.beginRecordingCountdown()
    }

    /** Called by the service once the countdown reaches zero. */
    internal fun beginRecording() {
        _mode.value = RecorderMode.RECORDING
        service?.startRecordingStatusTicker()
    }

    /** Cancels an in-progress countdown (button re-tap or volume-down). Touch exploration
     *  was never requested for it, so there's nothing to disable - only the timer stops. */
    fun cancelCountdown() {
        if (_mode.value != RecorderMode.COUNTDOWN) return
        _mode.value = RecorderMode.IDLE
        _countdownSecondsRemaining.value = null
        service?.cancelRecordingCountdown()
    }

    fun stopRecording() {
        _mode.value = RecorderMode.IDLE
        // Defensive: touch exploration is never requested on in the recording path
        // anymore, so this is normally a no-op clearing an already-clear flag - kept
        // in case a flag got left on by an older build/session.
        service?.setTouchExplorationRequested(false)
    }

    /** Stop via the "Stop Recording" button or volume-down (not the watchdog, which
     *  removes the pill itself instead - see RecordingAccessibilityService). Shows the
     *  "STOPPED - N steps" pill, which fades on its own a few seconds later. */
    fun stopRecordingManually() {
        stopRecording()
        service?.statusPill?.showStopped(_recordedSteps.value.size)
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
