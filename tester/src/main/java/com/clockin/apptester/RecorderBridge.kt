package com.clockin.apptester

import android.content.Context
import android.util.Log
import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.ReplayResult
import com.clockin.apptester.model.SavedFlow
import com.clockin.apptester.model.SavedFlowStore
import com.clockin.apptester.model.unlabeledStepWarning
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

    private val _savedFlows = MutableStateFlow<List<SavedFlow>>(emptyList())
    val savedFlows: StateFlow<List<SavedFlow>> = _savedFlows.asStateFlow()

    // Which saved flow (by SavedFlow.id) is currently loaded into _recordedSteps, if any -
    // needed now that several flows can share a packageName, so "active" can no longer be
    // derived from packageName alone the way it could with one flow per app.
    private val _activeFlowId = MutableStateFlow<String?>(null)
    val activeFlowId: StateFlow<String?> = _activeFlowId.asStateFlow()

    // Outcome of the most recently finished replay - null until the first one ever runs,
    // then stays showing that result until a later replay overwrites it. See the "Last
    // result" card in MainActivity.
    private val _lastResult = MutableStateFlow<ReplayResult?>(null)
    val lastResult: StateFlow<ReplayResult?> = _lastResult.asStateFlow()

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

    /** Called by the service once a replay loop ends, pass or fail. */
    internal fun recordReplayResult(result: ReplayResult) {
        _lastResult.value = result
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
        // A new, not-yet-saved recording doesn't correspond to any existing saved flow -
        // clear which one was highlighted so nothing stale stays marked active.
        _activeFlowId.value = null
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

        // Save-on-stop: every stop path (button, volume-down, watchdog) funnels through
        // here, so this one hook covers all three. Only when there's something to save -
        // doesn't touch anything else in this function. Always a new file, never a
        // replace - see SavedFlowStore.save. The new flow becomes active; every flow
        // already in the list is left exactly as it was.
        val steps = _recordedSteps.value
        val svc = service
        if (steps.isNotEmpty() && svc != null) {
            val targetPackage = SavedFlowStore.determineTargetPackage(svc, steps)
            if (targetPackage != null) {
                val saved = svc.saveFlow(targetPackage, steps)
                _savedFlows.value = SavedFlowStore.loadAll(svc)
                if (saved != null) _activeFlowId.value = saved.id
            } else {
                appendLog("Couldn't determine which app this recording was for - not saved.")
            }
        }
        steps.unlabeledStepWarning()?.let { appendLog(it) }
    }

    /** Called once from MainActivity.onCreate - pure file I/O via Context, independent
     *  of whether the accessibility service is connected yet. */
    fun loadSavedFlows(context: Context) {
        _savedFlows.value = SavedFlowStore.loadAll(context)
    }

    /** Loads a saved flow's steps as the current in-memory recording and marks it active
     *  by id. Clears the replay log too, so an old flow's results can't be mistaken for
     *  this one's. Only while idle, so this can't clobber an in-progress recording or
     *  replay. */
    fun selectSavedFlow(flow: SavedFlow) {
        if (_mode.value != RecorderMode.IDLE) return
        _recordedSteps.value = flow.steps
        _replayLog.value = emptyList()
        _activeFlowId.value = flow.id
    }

    /** Removes exactly this one flow's file - every other saved flow, including others
     *  for the same app, is untouched. If the deleted flow was the active one, clears it
     *  from the current in-memory recording too - otherwise the step list/replay button
     *  would keep referencing a flow that no longer has a file backing it. */
    fun deleteSavedFlow(context: Context, flow: SavedFlow) {
        SavedFlowStore.delete(context, flow)
        _savedFlows.update { it.filterNot { f -> f.id == flow.id } }
        if (_activeFlowId.value == flow.id) {
            _recordedSteps.value = emptyList()
            _activeFlowId.value = null
        }
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
