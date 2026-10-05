package com.clockin.apptester

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clockin.apptester.model.RecordedStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordingAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val statusPill = StatusPill(this)

    private var countdownJob: Job? = null
    private var pendingTouchExplorationToggle: Job? = null
    private var recordingStatusTicker: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Requested once, permanently - unlike touch exploration this isn't toggled
        // per-recording. onKeyEvent below only acts on it while RecorderMode.RECORDING
        // or RecorderMode.COUNTDOWN, so volume behaves normally the rest of the time.
        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }
        RecorderBridge.attachService(this)
    }

    override fun onDestroy() {
        statusPill.hide()
        RecorderBridge.detachService(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // TEMPORARY diagnostic logging for the Oct 4 investigation into why ordinary
        // taps don't produce TYPE_VIEW_CLICKED with touch exploration off. Remove once
        // that's resolved. Fetches its own source and recycles it immediately rather
        // than reusing the fetch below, so it doesn't change what that existing code does.
        val diagnosticSource = event.source
        Log.d(
            "RecordingA11yService",
            "event type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=${event.packageName} sourceNull=${diagnosticSource == null}"
        )
        diagnosticSource?.recycle()

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        // Skip the tester tool's own UI (e.g. taps on "Stop Recording"/"Replay") - the
        // service has no packageNames filter so it can later see into the wallet app,
        // but that means our own controls would otherwise get recorded as steps too.
        if (event.packageName?.toString() == packageName) return
        val source = event.source ?: return
        val step = RecordedStep(
            packageName = event.packageName?.toString() ?: run { source.recycle(); return },
            className = source.className?.toString(),
            resourceId = source.viewIdResourceName,
            text = source.text?.toString(),
            contentDescription = source.contentDescription?.toString()
        )
        source.recycle()
        RecorderBridge.appendStep(step)
    }

    override fun onInterrupt() {
        // Required override - cleanup happens in onDestroy.
    }

    /**
     * Emergency stop / cancel: volume-down during RecorderMode.RECORDING stops recording
     * and drops touch exploration the same way the "Stop Recording" button does - a
     * hardware-key escape hatch that still works if the touchscreen itself is
     * unresponsive. During RecorderMode.COUNTDOWN it instead cancels the countdown before
     * touch exploration is ever requested. Only consumed (return true, so the device's
     * volume doesn't also change) in those two modes; otherwise the key passes through
     * for normal volume behavior.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return super.onKeyEvent(event)

        return when (RecorderBridge.mode.value) {
            RecorderMode.COUNTDOWN -> {
                RecorderBridge.appendLog("Volume-down pressed - countdown cancelled.")
                RecorderBridge.cancelCountdown()
                true
            }
            RecorderMode.RECORDING -> {
                RecorderBridge.appendLog("Volume-down pressed - stopping recording and disabling touch exploration.")
                RecorderBridge.stopRecordingManually()
                true
            }
            else -> super.onKeyEvent(event)
        }
    }

    /**
     * Runs the pre-recording countdown, announcing each second to RecorderBridge and the
     * status pill. Touch exploration is never requested here and nothing gets recorded
     * during this window (recording only happens while RecorderMode.RECORDING - see
     * RecorderBridge.appendStep), so the user can leave this app, open the target app,
     * and use normal touches while it counts down. Only beginRecording() (called once it
     * reaches zero) requests touch exploration and flips the mode over.
     */
    fun beginRecordingCountdown() {
        // Clears any stale pill state (e.g. a still-fading "REPLAY OK" from a previous
        // run) immediately, rather than leaving it visible until the first countdown tick.
        statusPill.hide()
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in RECORDING_COUNTDOWN_SECONDS downTo 1) {
                RecorderBridge.setCountdownSecondsRemaining(remaining)
                statusPill.showCountdown(remaining)
                delay(1000L)
            }
            countdownJob = null
            RecorderBridge.setCountdownSecondsRemaining(null)
            RecorderBridge.beginRecording()
        }
    }

    /** Cancels an in-progress countdown. Touch exploration was never requested for it,
     *  so there's nothing to undo besides the timer itself; the pill comes down too. */
    fun cancelRecordingCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        RecorderBridge.setCountdownSecondsRemaining(null)
        statusPill.hide()
    }

    /**
     * Keeps the pill's "REC - N steps" line live while RecorderMode.RECORDING, and owns
     * the recording watchdog: an inactivity timer that resets to a fresh
     * RECORDING_INACTIVITY_TIMEOUT_MS every time a new step is captured, force-stopping
     * only once that much time passes with nothing captured - plus a hard cap that
     * force-stops RECORDING_HARD_CAP_MS after recording started no matter what. Both
     * read RecorderBridge.recordedSteps - the same read-only access the pill display
     * already used - rather than hooking into capture logic itself. The pill's
     * auto-stop line shows whichever limit is closer and jumps back up whenever a step
     * resets the inactivity timer. The force-stop path is unchanged from the old flat
     * watchdog: statusPill.hide() (no "STOPPED" message, the pill just comes down) then
     * RecorderBridge.stopRecording() (same touch-exploration-off path volume-down and
     * the button use). Exits on its own once mode leaves RECORDING for any other
     * reason; whichever stop path triggered that is responsible for what the pill
     * shows afterwards.
     */
    internal fun startRecordingStatusTicker() {
        // Clears any stale pill state immediately - the countdown's own pill already
        // covers the gap up to this point, so this is mostly belt-and-suspenders.
        statusPill.hide()
        recordingStatusTicker?.cancel()
        val startedAtMs = System.currentTimeMillis()
        var lastStepCount = -1
        var lastCapturedAtMs = startedAtMs
        recordingStatusTicker = scope.launch {
            while (RecorderBridge.mode.value == RecorderMode.RECORDING) {
                val now = System.currentTimeMillis()
                val steps = RecorderBridge.recordedSteps.value
                if (steps.size != lastStepCount) {
                    lastStepCount = steps.size
                    lastCapturedAtMs = now
                }

                val inactivityRemainingMs =
                    (RECORDING_INACTIVITY_TIMEOUT_MS - (now - lastCapturedAtMs)).coerceAtLeast(0)
                val hardCapRemainingMs =
                    (RECORDING_HARD_CAP_MS - (now - startedAtMs)).coerceAtLeast(0)

                if (inactivityRemainingMs <= 0 || hardCapRemainingMs <= 0) {
                    val reason = if (hardCapRemainingMs <= 0) {
                        "hard cap of ${RECORDING_HARD_CAP_MS / 60_000}min reached"
                    } else {
                        "no step captured for ${RECORDING_INACTIVITY_TIMEOUT_MS / 1000}s"
                    }
                    RecorderBridge.appendLog("Recording watchdog: $reason - forcing stop.")
                    statusPill.hide()
                    RecorderBridge.stopRecording()
                    recordingStatusTicker = null
                    return@launch
                }

                val remainingSeconds = minOf(inactivityRemainingMs, hardCapRemainingMs) / 1000
                val showDetail = now - lastCapturedAtMs < STEP_DETAIL_VISIBLE_MS
                statusPill.showRecording(steps.size, steps.lastOrNull()?.displayLabel(), remainingSeconds, showDetail)
                delay(1000L)
            }
            recordingStatusTicker = null
        }
    }

    /**
     * Touch exploration (the TalkBack mechanism: single tap explores/focuses, a second
     * tap activates) is the only way an ordinary direct touch produces a TYPE_VIEW_CLICKED
     * event on this platform - a plain tap with touch exploration off never does, which is
     * why recording must request it. It's a device-wide mode, so it's requested only for
     * the recording window and dropped immediately after, leaving normal touch behavior
     * (and replay, which clicks via performAction and never needed this) unaffected.
     *
     * The flag flip is deferred rather than applied inline: this is called from the same
     * Start/Stop Recording tap that's still being dispatched, and switching this system-wide
     * input mode mid-gesture has been observed to desync the platform's touch-exploration
     * input filter - it gets stuck intercepting all touchscreen input (hardware buttons still
     * work) until a hard reboot, since the stuck state lives in the system server, not this
     * app. Waiting for the current gesture to fully resolve before switching avoids the race.
     * Any previously scheduled toggle is cancelled first so a quick Start-then-Stop tap can't
     * land the two flag changes out of order.
     *
     * The recording watchdog used to be armed/disarmed here too; it now lives in
     * startRecordingStatusTicker, which already polls the same step list once a second
     * for the pill display and needs the identical "time since last step" tracking the
     * watchdog's inactivity timer needs - see that function.
     */
    fun setTouchExplorationRequested(requested: Boolean) {
        pendingTouchExplorationToggle?.cancel()
        pendingTouchExplorationToggle = scope.launch {
            delay(TOUCH_EXPLORATION_TOGGLE_DELAY_MS)
            val info = serviceInfo ?: return@launch
            info.flags = if (requested) {
                info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            } else {
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            }
            serviceInfo = info
        }
    }

    /** Re-finds each step's view in the current window and taps it, in order. */
    fun replay(steps: List<RecordedStep>) {
        scope.launch {
            // Starting a new replay clears any stale pill state immediately (e.g. a
            // "FLOW CHANGED" from a previous run still mid-fade) rather than leaving it
            // up through the app-launch delay below, where nothing else would overwrite it.
            statusPill.hide()

            // The tester's own UI is still in the foreground when this starts (the user
            // just tapped "Replay" here) - bring the recorded app back first, or every
            // step would be searched for in the wrong window and immediately "break".
            val targetPackage = steps.firstOrNull()?.packageName
            if (targetPackage != null && targetPackage != packageName) {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent == null) {
                    // getLaunchIntentForPackage also returns null when the target is installed
                    // but not visible to us (missing <queries> entry) - not just "not installed".
                    RecorderBridge.appendLog("Replay failed: can't launch $targetPackage: not visible or not installed")
                    RecorderBridge.replayFinished()
                    return@launch
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                RecorderBridge.appendLog("Bringing $targetPackage to foreground...")
                delay(TARGET_APP_LAUNCH_DELAY_MS)
            }
            var broke = false
            for ((index, step) in steps.withIndex()) {
                statusPill.showReplay(index + 1, steps.size)
                val root = rootInActiveWindow
                if (root == null) {
                    RecorderBridge.appendLog("Step ${index + 1}/${steps.size}: no active window - stopping.")
                    statusPill.showBreak(index + 1)
                    broke = true
                    break
                }
                val target = findNode(root, step)
                root.recycle()
                if (target == null) {
                    RecorderBridge.appendLog(
                        "Step ${index + 1}/${steps.size}: expected view not found " +
                            "(resourceId=${step.resourceId}, text=${step.text}) - flow changed."
                    )
                    statusPill.showBreak(index + 1)
                    broke = true
                    break
                }
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target.recycle()
                RecorderBridge.appendLog(
                    "Step ${index + 1}/${steps.size}: tapped " +
                        (step.resourceId ?: step.text ?: step.contentDescription ?: "unknown view")
                )
                delay(REPLAY_STEP_DELAY_MS)
            }
            if (!broke) {
                statusPill.showFinished()
            }
            RecorderBridge.appendLog("Replay finished.")
            RecorderBridge.replayFinished()
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, step: RecordedStep): AccessibilityNodeInfo? {
        step.resourceId?.let { id ->
            val match = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            if (match != null) return match
        }
        step.text?.let { text ->
            val match = root.findAccessibilityNodeInfosByText(text)
                .firstOrNull { it.className == step.className }
            if (match != null) return match
        }
        return null
    }

    companion object {
        private const val REPLAY_STEP_DELAY_MS = 800L
        private const val TARGET_APP_LAUNCH_DELAY_MS = 1500L

        // Gives the current tap's touch-up event time to finish resolving under the old
        // input mode before the touch-exploration flag switches - see setTouchExplorationRequested.
        private const val TOUCH_EXPLORATION_TOGGLE_DELAY_MS = 300L

        // Watchdog: force-stop recording after this long with no new step captured -
        // resets on every captured step. See startRecordingStatusTicker.
        private const val RECORDING_INACTIVITY_TIMEOUT_MS = 90_000L

        // Watchdog hard cap: force-stop recording this long after it started, no matter
        // what, regardless of activity. See startRecordingStatusTicker.
        private const val RECORDING_HARD_CAP_MS = 5 * 60_000L

        // Pre-recording window before touch exploration turns on - see beginRecordingCountdown.
        private const val RECORDING_COUNTDOWN_SECONDS = 7

        // How long the pill's second line stays visible after a new step is captured -
        // see startRecordingStatusTicker.
        private const val STEP_DETAIL_VISIBLE_MS = 2000L
    }
}

private fun RecordedStep.displayLabel(): String? = text ?: contentDescription ?: resourceId
