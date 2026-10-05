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

    fun showCountdown(secondsRemaining: Int) {
        render(line1Text = "STARTING IN $secondsRemaining", line2Text = null, isError = false)
    }

    fun showRecording(stepCount: Int, lastStepLabel: String?, autoStopSecondsRemaining: Long) {
        val minutes = autoStopSecondsRemaining / 60
        val seconds = autoStopSecondsRemaining % 60
        val autoStop = String.format(Locale.US, "%d:%02d", minutes, seconds)
        val last = lastStepLabel ?: "(unlabeled)"
        render(
            line1Text = "REC - $stepCount steps",
            line2Text = "last: $last | auto-stop $autoStop | VOL-DOWN STOP",
            isError = false
        )
    }

    fun showStopped(stepCount: Int) {
        render(line1Text = "STOPPED - $stepCount steps", line2Text = null, isError = false)
        mainHandler.postDelayed({ hide() }, STOPPED_FADE_DELAY_MS)
    }

    fun showReplay(stepNumber: Int, totalSteps: Int) {
        render(line1Text = "REPLAY - step $stepNumber/$totalSteps", line2Text = null, isError = false)
    }

    fun showBreak(stepNumber: Int) {
        render(line1Text = "FLOW CHANGED - step $stepNumber", line2Text = null, isError = true)
    }

    /** Removes the overlay entirely. Called on service disconnect, countdown/recording
     *  cancellation, and when the 60s watchdog fires - see the call sites in
     *  RecordingAccessibilityService and RecorderBridge. */
    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        rootView?.let { view -> runCatching { windowManager?.removeView(view) } }
        rootView = null
        line1 = null
        line2 = null
        dot = null
    }

    private fun render(line1Text: String, line2Text: String?, isError: Boolean) {
        ensureAdded()
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
        val dotView = View(service).apply { background = dotDrawable }

        val sMark = TextView(service).apply {
            text = "S"
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
        }

        val textLine1 = TextView(service).apply {
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 13f
        }
        line1 = textLine1

        val textLine2 = TextView(service).apply {
            setTextColor(ELECTRIC_GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            visibility = View.GONE
        }
        line2 = textLine2

        val textStack = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
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
    }
}
