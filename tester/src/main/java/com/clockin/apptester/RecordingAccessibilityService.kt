package com.clockin.apptester

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
    private var recordingWatchdog: Job? = null
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
     * Keeps the pill's "REC - N steps" line live while RecorderMode.RECORDING: step
     * count, the most recently captured step's label, and time left before the watchdog
     * (reads RECORDING_WATCHDOG_TIMEOUT_MS below purely for display - the watchdog's own
     * timing in setTouchExplorationRequested is untouched) auto-stops things. Exits on
     * its own once mode leaves RECORDING; whichever stop path triggered that is
     * responsible for what the pill shows afterwards.
     */
    internal fun startRecordingStatusTicker() {
        recordingStatusTicker?.cancel()
        val startedAtMs = System.currentTimeMillis()
        recordingStatusTicker = scope.launch {
            while (RecorderBridge.mode.value == RecorderMode.RECORDING) {
                val elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000
                val remainingSeconds =
                    (RECORDING_WATCHDOG_TIMEOUT_MS / 1000 - elapsedSeconds).coerceAtLeast(0)
                val steps = RecorderBridge.recordedSteps.value
                statusPill.showRecording(steps.size, steps.lastOrNull()?.displayLabel(), remainingSeconds)
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
     * Turning it on also arms a 60s watchdog that force-stops recording regardless of UI
     * state - a second safety net alongside volume-down, for cases where neither the
     * touchscreen nor the hardware key path is usable. Turning it off (by button, volume
     * key, or the watchdog itself) disarms any pending watchdog so it can't double-fire.
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

        recordingWatchdog?.cancel()
        recordingWatchdog = if (requested) {
            scope.launch {
                delay(RECORDING_WATCHDOG_TIMEOUT_MS)
                recordingWatchdog = null
                RecorderBridge.appendLog(
                    "Recording watchdog: no stop received after " +
                        "${RECORDING_WATCHDOG_TIMEOUT_MS / 1000}s - forcing stop."
                )
                statusPill.hide()
                RecorderBridge.stopRecording()
            }
        } else {
            null
        }
    }

    /** Re-finds each step's view in the current window and taps it, in order. */
    fun replay(steps: List<RecordedStep>) {
        scope.launch {
            // The tester's own UI is still in the foreground when this starts (the user
            // just tapped "Replay" here) - bring the recorded app back first, or every
            // step would be searched for in the wrong window and immediately "break".
            val targetPackage = steps.firstOrNull()?.packageName
            if (targetPackage != null && targetPackage != packageName) {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    RecorderBridge.appendLog("Bringing $targetPackage to foreground...")
                    delay(TARGET_APP_LAUNCH_DELAY_MS)
                } else {
                    RecorderBridge.appendLog("Replay warning: couldn't launch $targetPackage - not installed?")
                }
            }
            for ((index, step) in steps.withIndex()) {
                statusPill.showReplay(index + 1, steps.size)
                val root = rootInActiveWindow
                if (root == null) {
                    RecorderBridge.appendLog("Step ${index + 1}/${steps.size}: no active window - stopping.")
                    statusPill.showBreak(index + 1)
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

        // Safety net: auto-stop recording (and touch exploration with it) if nothing
        // else stopped it within a minute - see setTouchExplorationRequested.
        private const val RECORDING_WATCHDOG_TIMEOUT_MS = 60_000L

        // Pre-recording window before touch exploration turns on - see beginRecordingCountdown.
        private const val RECORDING_COUNTDOWN_SECONDS = 7
    }
}

private fun RecordedStep.displayLabel(): String? = text ?: contentDescription ?: resourceId
