package com.clockin.apptester

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

    // TEMPORARY experiment mode (see startExperimentRecordingCountdown): whether the
    // current RecorderMode.RECORDING session never requested touch exploration, plus
    // live counters for the pill's line 2 while it runs. Plain vars, not StateFlow -
    // only ever read/written from the main thread (Compose click handlers and
    // AccessibilityService callbacks), same as every other mutable field here.
    internal var isExperimentRecording = false
        private set
    private var experimentClickCount = 0
    private var experimentDroppedCount = 0

    internal fun recordExperimentClick() {
        experimentClickCount++
    }

    internal fun recordExperimentDropped() {
        experimentDroppedCount++
    }

    internal fun experimentClickCount(): Int = experimentClickCount

    internal fun experimentDroppedCount(): Int = experimentDroppedCount

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

    /** Called by the service's countdown loop on every tick; null once it ends. */
    internal fun setCountdownSecondsRemaining(seconds: Int?) {
        _countdownSecondsRemaining.value = seconds
    }

    /**
     * Starts the pre-recording countdown. Touch exploration stays off and nothing is
     * recorded while it counts down - the service only requests touch exploration and
     * flips into RecorderMode.RECORDING once it reaches zero (see beginRecording).
     */
    fun startRecordingCountdown() {
        if (_mode.value != RecorderMode.IDLE) return
        val svc = service
        if (svc == null) {
            appendLog("Cannot start recording: accessibility service not connected.")
            return
        }
        _recordedSteps.value = emptyList()
        _mode.value = RecorderMode.COUNTDOWN
        svc.beginRecordingCountdown()
    }

    /** Called by the service once the countdown reaches zero - the only point where
     *  touch exploration actually gets requested. */
    internal fun beginRecording() {
        _mode.value = RecorderMode.RECORDING
        service?.setTouchExplorationRequested(true)
        service?.startRecordingStatusTicker()
    }

    /**
     * TEMPORARY experiment entry point for the "Record (normal touch, experiment)"
     * button. Mirrors startRecordingCountdown()/beginRecording() exactly, except the
     * countdown finishes into beginExperimentRecording() instead, which never requests
     * touch exploration - capture then depends entirely on whether the target app emits
     * TYPE_VIEW_CLICKED for ordinary one-finger touches on its own. Reuses
     * RecorderMode.RECORDING (not a separate mode), so capture, the watchdog, and
     * volume-down all keep working unmodified - none of them care how touch exploration
     * got into its current state, only what RecorderMode.mode currently is.
     */
    fun startExperimentRecordingCountdown() {
        if (_mode.value != RecorderMode.IDLE) return
        val svc = service
        if (svc == null) {
            appendLog("Cannot start recording: accessibility service not connected.")
            return
        }
        _recordedSteps.value = emptyList()
        experimentClickCount = 0
        experimentDroppedCount = 0
        _mode.value = RecorderMode.COUNTDOWN
        svc.beginRecordingCountdown { beginExperimentRecording() }
    }

    /** TEMPORARY - see startExperimentRecordingCountdown. Deliberately skips
     *  setTouchExplorationRequested(true), the only difference from beginRecording(). */
    internal fun beginExperimentRecording() {
        _mode.value = RecorderMode.RECORDING
        isExperimentRecording = true
        service?.startRecordingStatusTicker(experimentMode = true)
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
        isExperimentRecording = false
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
