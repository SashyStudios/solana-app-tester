package com.clockin.apptester

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Display-only floating status overlay drawn directly by the accessibility service as a
 * TYPE_ACCESSIBILITY_OVERLAY window - a window type a bound accessibility service can add
 * without the SYSTEM_ALERT_WINDOW permission/prompt. FLAG_NOT_TOUCHABLE and
 * FLAG_NOT_FOCUSABLE are set on the whole window - the glow is just the outer view in the
 * same window, so it's covered by the same flags - so every touch passes straight through
 * to whatever is underneath and this overlay can never itself generate a recorded step.
 */
class StatusPill(private val service: AccessibilityService) {

    // Deferred to first use (see ensureAdded/hide, always from onServiceConnected onward) -
    // this used to run eagerly here, but field initializers execute during the service's
    // own constructor, before Android attaches a Context, which crashed getSystemService.
    private val windowManager: WindowManager? by lazy { service.getSystemService(WindowManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var line1: TextView? = null
    private var line2: TextView? = null
    private var dot: GradientDrawable? = null

    private var dotLit = false
    private val pulseRunnable = object : Runnable {
        override fun run() {
            dotLit = !dotLit
            dot?.alpha = if (dotLit) 255 else 70
            mainHandler.postDelayed(this, DOT_PULSE_INTERVAL_MS)
        }
    }

    // Tracks a scheduled auto-hide (showStopped/showBreak/showFinished) so a later
    // render() can cancel it specifically - removeCallbacksAndMessages(null) would also
    // cancel the pulse dot's own repeating schedule, stopping it from pulsing further.
    private var pendingHide: Runnable? = null

    fun showCountdown(secondsRemaining: Int) {
        render(line1Text = "STARTING IN $secondsRemaining", line2Text = null, isError = false)
    }

    /** showDetail controls the second line - callers show it only briefly (a few
     *  seconds) after a new step is captured, so the pill is a single line the rest
     *  of the time it's recording. */
    fun showRecording(stepCount: Int, lastStepLabel: String?, autoStopSecondsRemaining: Long, showDetail: Boolean) {
        val detail = if (showDetail) {
            val minutes = autoStopSecondsRemaining / 60
            val seconds = autoStopSecondsRemaining % 60
            val autoStop = String.format(Locale.US, "%d:%02d", minutes, seconds)
            val last = lastStepLabel ?: "(unlabeled)"
            "last: $last | auto-stop $autoStop | VOL-DOWN STOP"
        } else {
            null
        }
        render(line1Text = "REC - $stepCount steps", line2Text = detail, isError = false)
    }

    fun showStopped(stepCount: Int) {
        render(line1Text = "STOPPED - $stepCount steps", line2Text = null, isError = false)
        scheduleHide(STOPPED_FADE_DELAY_MS)
    }

    fun showReplay(stepNumber: Int, totalSteps: Int, stepLabel: String) {
        render(line1Text = "REPLAY - step $stepNumber/$totalSteps", line2Text = stepLabel, isError = false)
    }

    /** A replay break used to leave the pill showing "FLOW CHANGED" forever - it now
     *  fades out on its own, same as every other terminal pill state. */
    fun showBreak(stepNumber: Int) {
        render(line1Text = "FLOW CHANGED - step $stepNumber", line2Text = null, isError = true)
        scheduleHide(BREAK_FADE_DELAY_MS)
    }

    /** A replay that completes every step without a break. */
    fun showFinished() {
        render(line1Text = "REPLAY OK", line2Text = null, isError = false)
        scheduleHide(FINISHED_FADE_DELAY_MS)
    }

    private fun scheduleHide(delayMs: Long) {
        pendingHide?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { hide() }
        pendingHide = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** Removes the overlay entirely. Called on service disconnect, countdown/recording
     *  cancellation, when the watchdog fires, and by scheduleHide's own fade-out timers
     *  - see the call sites in RecordingAccessibilityService and RecorderBridge. */
    fun hide() {
        pendingHide = null
        mainHandler.removeCallbacksAndMessages(null)
        // Safety: the pill must end up fully visible or fully removed, never stuck
        // invisible - force both back to normal before tearing the window down.
        rootView?.alpha = 1f
        rootView?.visibility = View.VISIBLE
        rootView?.let { view -> runCatching { windowManager?.removeView(view) } }
        rootView = null
        line1 = null
        line2 = null
        dot = null
    }

    private fun render(line1Text: String, line2Text: String?, isError: Boolean) {
        // Starting a new state (countdown/recording/replay/stopped/finished/break)
        // clears any fade-out timer left over from whatever the pill showed before -
        // otherwise a stale showBreak()/showFinished() timer could later hide() this
        // new, unrelated content out from under it. Doesn't touch the pulse dot's own
        // repeating schedule - see pendingHide's declaration.
        pendingHide?.let { mainHandler.removeCallbacks(it) }
        pendingHide = null
        ensureAdded()
        // Same invariant as hide(): whenever we're showing content, it must actually
        // be visible, regardless of anything that may have faded it out before.
        rootView?.alpha = 1f
        rootView?.visibility = View.VISIBLE
        val color = if (isError) ERROR_RED else ELECTRIC_GREEN
        line1?.text = line1Text
        line1?.setTextColor(color)
        line2?.text = line2Text
        line2?.visibility = if (line2Text == null) View.GONE else View.VISIBLE
        line2?.setTextColor(color)
        dot?.setColor(color)
    }

    private fun ensureAdded() {
        if (rootView != null) return
        val wm = windowManager ?: return

        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ELECTRIC_GREEN)
        }
        dot = dotDrawable
        val dotView = View(service).apply {
            background = dotDrawable
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val sMark = TextView(service).apply {
            text = "S"
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val textLine1 = TextView(service).apply {
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        line1 = textLine1

        val textLine2 = TextView(service).apply {
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        line2 = textLine2

        val textStack = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(textLine1)
            addView(textLine2)
        }

        val pillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(PILL_BACKGROUND_ALPHA, 0, 0, 0))
        }

        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable
            setPadding(dp(14), dp(8), dp(14), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

            addView(
                sMark,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
            )
            addView(
                dotView,
                LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    rightMargin = dp(8)
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
            addView(textStack)
        }

        val glowDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(GLOW_ALPHA, 0, 255, 136))
        }

        val glow = FrameLayout(service).apply {
            background = glowDrawable
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // Primary fix for the touch-exploration hit-test problem: a window with no
            // accessibility content anywhere in it gets skipped when touch exploration
            // decides what's at a given point, so taps fall through to whatever's
            // actually underneath instead of landing on this overlay. NO_HIDE_DESCENDANTS
            // on the root already covers the whole subtree; it's set on each child too
            // for clarity and so it survives any future restructuring of this hierarchy.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(pill)
        }

        rootView = glow

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx() + dp(8)
        }

        val added = runCatching { wm.addView(glow, params) }.isSuccess
        if (!added) {
            rootView = null
            line1 = null
            line2 = null
            dot = null
            return
        }

        dotLit = false
        mainHandler.removeCallbacks(pulseRunnable)
        mainHandler.post(pulseRunnable)
    }

    private fun statusBarHeightPx(): Int {
        val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            service.resources.getDimensionPixelSize(resourceId)
        } else {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, service.resources.displayMetrics
            ).toInt()
        }
    }

    companion object {
        private const val ELECTRIC_GREEN = 0xFF00FF88.toInt()
        private const val ERROR_RED = 0xFFFF4444.toInt()
        private const val PILL_BACKGROUND_ALPHA = 140 // ~55% of 255
        private const val GLOW_ALPHA = 51 // ~20% of 255
        private const val DOT_PULSE_INTERVAL_MS = 1500L
        private const val STOPPED_FADE_DELAY_MS = 3000L
        private const val BREAK_FADE_DELAY_MS = 8000L
        private const val FINISHED_FADE_DELAY_MS = 3000L
    }
}
