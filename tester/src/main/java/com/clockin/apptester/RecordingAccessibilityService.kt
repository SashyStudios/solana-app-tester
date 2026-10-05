package com.clockin.apptester

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.primaryLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class RecordingAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val statusPill = StatusPill(this)

    private var countdownJob: Job? = null
    private var pendingTouchExplorationToggle: Job? = null
    private var recordingStatusTicker: Job? = null

    /**
     * Pre-tap label cache for the toggle-button label bug (see CLAUDE.md status): the
     * only signal we get for a step's label is TYPE_VIEW_CLICKED, which Android fires
     * from View.performClick() only after the click handler already ran, so a
     * self-toggling element (e.g. SeekShot's "AllShot" flipping to "Cancel") would
     * otherwise be recorded with its post-tap text. Keyed by "packageName/resourceId" -
     * resourceId alone isn't guaranteed unique across different target apps. Each entry
     * holds the two most recent distinct values seen for that key, so a click landing
     * right after its own self-triggered content-change can still read the value from
     * just before that change (see RecordedStep's doc comment for the full reasoning).
     * Only populated while RecorderMode.RECORDING (see onAccessibilityEvent); reset at
     * the start of each recording session via clearLabelCache().
     */
    private data class Label(val text: String?, val contentDescription: String?)
    private data class LabelHistory(val previous: Label?, val current: Label?)
    private val labelCache = mutableMapOf<String, LabelHistory>()

    internal fun clearLabelCache() {
        labelCache.clear()
    }

    /** Records an observed label for a resourceId, shifting the previous value down a
     *  slot only when something actually changed - skips allocating a new map entry for
     *  the (overwhelming majority) no-op case, since this runs on every content-changed
     *  event for a matching element. */
    private fun observeLabel(packageName: String, resourceId: String, text: String?, contentDescription: String?) {
        val key = "$packageName/$resourceId"
        val existing = labelCache[key]
        val currentLabel = existing?.current
        if (currentLabel != null && currentLabel.text == text && currentLabel.contentDescription == contentDescription) {
            return
        }
        labelCache[key] = LabelHistory(previous = existing?.current, current = Label(text, contentDescription))
    }

    /** Full-tree label snapshot - only called on TYPE_WINDOW_STATE_CHANGED (a new
     *  screen), never per content-changed event, which only updates the single node
     *  that changed (event.source) instead. Screen transitions are comparatively rare
     *  next to how often content changes, so the heavier walk is reserved for them. */
    private fun snapshotLabels(node: AccessibilityNodeInfo, packageName: String) {
        if (node.isClickable) {
            node.viewIdResourceName?.let { resourceId ->
                observeLabel(packageName, resourceId, node.text?.toString(), node.contentDescription?.toString())
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                snapshotLabels(child, packageName)
            } finally {
                child.recycle()
            }
        }
    }

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
        // touchExploration reads the live flag straight off serviceInfo rather than
        // tracking a separate field, so this stays a pure read with zero risk to
        // setTouchExplorationRequested's own deferred-toggle logic.
        val diagnosticSource = event.source
        val touchExplorationOn =
            (serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        Log.d(
            "RecordingA11yService",
            "event type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "package=${event.packageName} sourceNull=${diagnosticSource == null} " +
                "touchExploration=${if (touchExplorationOn) "on" else "off"}"
        )
        diagnosticSource?.recycle()

        val isRecording = RecorderBridge.mode.value == RecorderMode.RECORDING

        // Seeds the label cache for every clickable element on a new screen, well
        // before anything on it could be tapped - see snapshotLabels.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isRecording) {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            snapshotLabels(root, pkg)
                        } finally {
                            root.recycle()
                        }
                    }
                }
            }
            return
        }

        // Lighter per-event update: only the single node named by event.source, not a
        // full tree walk - this fires far more often than TYPE_WINDOW_STATE_CHANGED.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (isRecording) {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != packageName) {
                    val src = event.source
                    val resId = src?.viewIdResourceName
                    if (resId != null) {
                        observeLabel(pkg, resId, src.text?.toString(), src.contentDescription?.toString())
                    }
                    src?.recycle()
                }
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        // Skip the tester tool's own UI (e.g. taps on "Stop Recording"/"Replay") - the
        // service has no packageNames filter so it can later see into the wallet app,
        // but that means our own controls would otherwise get recorded as steps too.
        if (event.packageName?.toString() == packageName) return
        val source = event.source ?: return
        val stepPackageName = event.packageName?.toString()
        if (stepPackageName == null) {
            source.recycle()
            return
        }
        val resourceId = source.viewIdResourceName
        val history = resourceId?.let { labelCache["$stepPackageName/$it"] }
        val preTap = history?.previous ?: history?.current
        val step = RecordedStep(
            packageName = stepPackageName,
            className = source.className?.toString(),
            resourceId = resourceId,
            text = source.text?.toString(),
            contentDescription = source.contentDescription?.toString(),
            preTapText = preTap?.text,
            preTapContentDescription = preTap?.contentDescription
        )
        source.recycle()
        RecorderBridge.appendStep(step)
        if (isRecording) {
            // TEMPORARY - pre-tap label fix investigation (see CLAUDE.md status). Remove
            // once this has been verified reliable on-device.
            Log.d(
                "RecordingA11yService",
                "captured step resourceId=$resourceId preTapText=${step.preTapText} postTapText=${step.text}"
            )
        }
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
     * and use normal touches while it counts down. beginRecording() (called once it
     * reaches zero) flips the mode over - touch exploration is never requested anywhere
     * in this path (see RecorderBridge.startRecordingCountdown).
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
                statusPill.showRecording(steps.size, steps.lastOrNull()?.primaryLabel(), remainingSeconds, showDetail)
                delay(1000L)
            }
            recordingStatusTicker = null
        }
    }

    /**
     * No longer called with true anywhere: recording used to request touch exploration
     * (the TalkBack mechanism - single tap explores/focuses, a second tap activates) to
     * make an ordinary direct touch produce a TYPE_VIEW_CLICKED event, which classic
     * Android Views emit on their own but Compose does not. The demo app is classic
     * Views now (see CLAUDE.md's Oct 5 decision), so recording no longer needs it - see
     * RecorderBridge.startRecordingCountdown/beginRecording. Kept, called only with
     * false from RecorderBridge.stopRecording(), as defensive cleanup in case a flag got
     * left on by an older build/session; the capability declaration
     * (canRequestTouchExplorationMode) and this method are otherwise unused infrastructure.
     *
     * The flag flip stays deferred (not applied inline) for the same reason it always
     * was: switching this system-wide input mode has been observed to desync the
     * platform's touch-exploration input filter if done mid-gesture - it gets stuck
     * intercepting all touchscreen input (hardware buttons still work) until a hard
     * reboot, since the stuck state lives in the system server, not this app. Any
     * previously scheduled toggle is cancelled first so calls can't land out of order.
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
                // CLEAR_TASK forces the target back to its launch screen instead of
                // resuming wherever a previous replay attempt left it mid-flow - without
                // it, run-to-run results can vary just from inherited app state. It only
                // resets the activity stack: the process may be reused rather than
                // killed, and saved data (SharedPreferences, databases, files) and
                // granted permissions are untouched - the log line says so explicitly.
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                RecorderBridge.appendLog(
                    "Launching $targetPackage with a clean task " +
                        "(activity stack reset; saved data and permissions are not reset)"
                )
                delay(TARGET_APP_LAUNCH_DELAY_MS)
            }
            var completedSteps = 0
            var broke = false
            for ((index, step) in steps.withIndex()) {
                statusPill.showReplay(index + 1, steps.size, step.primaryLabel())
                when (val lookup = findNodeWithRetry(step)) {
                    is ReplayLookup.NotFound -> {
                        RecorderBridge.appendLog(breakExplanation(index, steps.size, step, lookup.onScreen))
                        statusPill.showBreak(index + 1)
                        broke = true
                    }
                    is ReplayLookup.Found -> {
                        val result = lookup.result
                        if (result.matchedByPostTapText != null) {
                            RecorderBridge.appendLog(
                                "Step ${index + 1}/${steps.size}: matched by post-tap text " +
                                    "'${result.matchedByPostTapText}' (no pre-tap label recorded)"
                            )
                        }
                        val target = result.node
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        target.recycle()
                        RecorderBridge.appendLog("Step ${index + 1}/${steps.size}: tapped ${step.primaryLabel()}")
                        completedSteps++
                    }
                }
                if (broke) break
                // Kept exactly as it was: 1000ms between a successful step and the next
                // step's first lookup attempt - the retry loop above adds up to 3 more
                // seconds only when a step isn't found right away, it doesn't change the
                // pacing of steps that succeed immediately.
                delay(REPLAY_STEP_DELAY_MS)
            }
            if (!broke) {
                statusPill.showFinished()
            }
            RecorderBridge.appendLog("Replay finished: $completedSteps of ${steps.size} steps ran.")
            RecorderBridge.replayFinished()
        }
    }

    /** matchedByPostTapText is non-null only when the match came from the third tier
     *  below (step.text/contentDescription) - the caller logs that case explicitly,
     *  since it means no pre-tap label was available to prefer instead. */
    private data class MatchResult(val node: AccessibilityNodeInfo, val matchedByPostTapText: String? = null)

    private sealed class ReplayLookup {
        data class Found(val result: MatchResult) : ReplayLookup()
        data class NotFound(val onScreen: List<ClickableElement>) : ReplayLookup()
    }

    /** Plain data only, by design - never an AccessibilityNodeInfo reference, so nothing
     *  returned from listClickableElements/collectClickableElements can ever be used
     *  after the root it came from is recycled. */
    private data class ClickableElement(val resourceId: String?, val label: String, val className: String?)

    /**
     * Polls for a match every NODE_SEARCH_POLL_INTERVAL_MS, up to NODE_SEARCH_TIMEOUT_MS
     * total, instead of the single immediate lookup this used to be - a screen that
     * takes a moment longer than REPLAY_STEP_DELAY_MS to finish rendering after the
     * previous step no longer reads as a false break. Covers both "no active window
     * yet" and "window present but node not found" the same way: either just means
     * try again next tick. On every failed attempt (root present, no match), captures
     * what's actually on screen - walked and converted to plain ClickableElement data
     * before that root is recycled, so lastOnScreen never holds a node reference across
     * the recycle. Only the most recent snapshot is kept; if the timeout expires, that's
     * what the break explanation reports.
     */
    private suspend fun findNodeWithRetry(step: RecordedStep): ReplayLookup {
        var lastOnScreen: List<ClickableElement> = emptyList()
        val found = withTimeoutOrNull(NODE_SEARCH_TIMEOUT_MS) {
            var result: MatchResult? = null
            while (result == null) {
                val root = rootInActiveWindow
                if (root != null) {
                    result = findNode(root, step)
                    if (result == null) {
                        lastOnScreen = listClickableElements(root, MAX_ON_SCREEN_ELEMENTS)
                    }
                    root.recycle()
                }
                if (result == null) delay(NODE_SEARCH_POLL_INTERVAL_MS)
            }
            result
        }
        return if (found != null) ReplayLookup.Found(found) else ReplayLookup.NotFound(lastOnScreen)
    }

    /** "expected <name>, not found", up to 5 clickable elements currently on screen, and
     *  the closest match (same class) flagged as possibly renamed - the minimal break
     *  explanation from CLAUDE.md's "Break explanations" section. Observable differences
     *  only: this never claims to know what changed in the target app's code. */
    private fun breakExplanation(
        index: Int,
        totalSteps: Int,
        step: RecordedStep,
        onScreen: List<ClickableElement>
    ): String {
        val closest = onScreen.firstOrNull { it.className == step.className }
        return buildString {
            append("Step ${index + 1}/$totalSteps: expected ${step.primaryLabel()}, not found.")
            if (onScreen.isNotEmpty()) {
                append(" On screen: ")
                append(onScreen.joinToString("; ") { "${it.resourceId ?: "(no id)"} '${it.label}'" })
            }
            if (closest != null) {
                append(" Possibly renamed: ${closest.resourceId ?: "(no id)"} '${closest.label}'.")
            }
        }
    }

    private fun listClickableElements(root: AccessibilityNodeInfo, limit: Int): List<ClickableElement> {
        val found = mutableListOf<ClickableElement>()
        collectClickableElements(root, limit, found)
        return found
    }

    /** Never recycles root itself - same ownership convention as snapshotLabels, the
     *  caller recycles it. Recycles every child it fetches via getChild(). Stops
     *  early once limit is reached so a large/deep tree doesn't get fully walked
     *  just to report 5 elements. */
    private fun collectClickableElements(node: AccessibilityNodeInfo, limit: Int, into: MutableList<ClickableElement>) {
        if (into.size >= limit) return
        if (node.isClickable) {
            val label = node.text?.toString()
                ?: node.contentDescription?.toString()
                ?: node.viewIdResourceName
                ?: "(unlabeled)"
            into.add(ClickableElement(node.viewIdResourceName, label, node.className?.toString()))
        }
        for (i in 0 until node.childCount) {
            if (into.size >= limit) return
            val child = node.getChild(i) ?: continue
            try {
                collectClickableElements(child, limit, into)
            } finally {
                child.recycle()
            }
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, step: RecordedStep): MatchResult? {
        step.resourceId?.let { id ->
            val match = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            if (match != null) return MatchResult(match)
        }
        // Pre-tap label, not step.text - step.text is the post-tap value, which for a
        // self-toggling element doesn't exist on screen yet at this point in replay
        // (the tap that would produce it hasn't happened), so searching for it would
        // find nothing and falsely report a break. See CLAUDE.md status.
        val preTapText = step.preTapText ?: step.preTapContentDescription
        preTapText?.let { text ->
            val match = root.findAccessibilityNodeInfosByText(text)
                .firstOrNull { it.className == step.className }
            if (match != null) return MatchResult(match)
        }
        // Last resort, only reached with no resourceId match and no pre-tap label to
        // try: falls back to the post-tap value so a resourceId-less element isn't left
        // with nothing to match on at all (it would otherwise always break - the label
        // cache only ever has entries for elements with a resourceId, so one without
        // can never get a pre-tap value here). primaryLabel()/the step list/pill never
        // show this value as the step's name - only findNode uses it, as a last resort.
        val postTapText = step.text ?: step.contentDescription
        postTapText?.let { text ->
            val match = root.findAccessibilityNodeInfosByText(text)
                .firstOrNull { it.className == step.className }
            if (match != null) return MatchResult(match, matchedByPostTapText = text)
        }
        return null
    }

    companion object {
        // Delay between replay steps.
        private const val REPLAY_STEP_DELAY_MS = 1000L
        private const val TARGET_APP_LAUNCH_DELAY_MS = 1500L

        // How long and how often findNodeWithRetry polls before declaring a break.
        private const val NODE_SEARCH_TIMEOUT_MS = 3000L
        private const val NODE_SEARCH_POLL_INTERVAL_MS = 300L

        // Cap on how many on-screen clickable elements a break explanation lists.
        private const val MAX_ON_SCREEN_ELEMENTS = 5

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
