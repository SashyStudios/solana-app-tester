package com.clockin.apptester.model

/**
 * Raw recorded click - no MWA awareness yet (that layers on top in a later build day).
 * resourceId is the primary match key on replay; text/className are fallbacks for
 * views without a resource id (common in some wallet-app dialogs).
 *
 * text/contentDescription are read from event.source AFTER the click handler already
 * ran - Android fires TYPE_VIEW_CLICKED from View.performClick() only once onClick()
 * returns - so for an element that changes its own label when tapped (a toggle button,
 * e.g. SeekShot's "AllShot" flipping to "Cancel"), these are post-tap values, not what
 * was actually on screen when the user tapped. preTapText/preTapContentDescription hold
 * whatever RecordingAccessibilityService's label cache had for this element just before
 * the tap, when available. Always use primaryLabel(), never text/contentDescription
 * directly, for anything shown to a person.
 */
data class RecordedStep(
    val packageName: String,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val preTapText: String?,
    val preTapContentDescription: String?
)

/**
 * The name to show for this step. Pre-tap label if the cache had one, else the
 * resource ID, else an explicit "(unlabeled)" - deliberately never falls back to the
 * post-tap text/contentDescription, since that's exactly the value that can be wrong
 * for a self-toggling element. See postTapDetail() for showing that value safely.
 */
fun RecordedStep.primaryLabel(): String =
    preTapText ?: preTapContentDescription ?: resourceId ?: "(unlabeled)"

/** The post-tap text/contentDescription, only when it actually differs from
 *  primaryLabel() - so an ordinary (non-toggling) step shows nothing extra. Callers
 *  should mark this clearly as post-tap (e.g. "shows '<text>' after tap"), never
 *  present it as the step's name. */
fun RecordedStep.postTapDetail(): String? {
    val postTap = text ?: contentDescription
    return postTap?.takeIf { it != primaryLabel() }
}
