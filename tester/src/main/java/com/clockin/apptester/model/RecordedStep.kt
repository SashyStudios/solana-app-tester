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
    val preTapContentDescription: String?,
    /** True when a label looked sensitive and was stored as null instead. Only the fact
     *  is kept, never the value. Persisted only when true (omitted otherwise). */
    val labelRemoved: Boolean = false
)

const val SENSITIVE_MARKER = "⚠ sensitive-looking label removed"

private val DIGIT_RUN = Regex("\\d{5,}")
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
private val HEX = Regex("[0-9a-fA-F]+")
private val BASE58 = Regex("[1-9A-HJ-NP-Za-km-z]+")

/** True for a label with more than 4 digits in a row, an email address, or any
 *  whitespace-separated token over 20 characters that is entirely hex or base58
 *  characters. Resource IDs are never passed through this. */
fun looksSensitive(label: String): Boolean {
    if (DIGIT_RUN.containsMatchIn(label) || EMAIL.containsMatchIn(label)) return true
    return label.split(Regex("\\s+")).any { token ->
        token.length > 20 && (HEX.matches(token) || BASE58.matches(token))
    }
}

/** The one place a step is built from raw node/event values: any sensitive-looking
 *  text, contentDescription or pre-tap label becomes null, and the step records that
 *  something was removed. The removed value is never logged or kept. */
fun buildScrubbedStep(
    packageName: String,
    className: String?,
    resourceId: String?,
    text: String?,
    contentDescription: String?,
    preTapText: String?,
    preTapContentDescription: String?
): RecordedStep {
    var removed = false
    fun scrub(value: String?): String? {
        if (value != null && looksSensitive(value)) {
            removed = true
            return null
        }
        return value
    }
    return RecordedStep(
        packageName = packageName,
        className = className,
        resourceId = resourceId,
        text = scrub(text),
        contentDescription = scrub(contentDescription),
        preTapText = scrub(preTapText),
        preTapContentDescription = scrub(preTapContentDescription),
        labelRemoved = removed
    )
}

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

/** True when nothing distinguishes this step at all - no resourceId, and no text or
 *  contentDescription in either their pre-tap or post-tap form. findNode()'s three-tier
 *  match (resourceId, pre-tap text, post-tap text) has nothing to go on for a step like
 *  this, so replay can only ever ride on step ordering plus whatever's drawn at that
 *  position - it isn't actually finding this element by any identity. See CLAUDE.md
 *  status, "Unlabeled step warning". */
fun RecordedStep.hasNoIdentity(): Boolean =
    resourceId == null && text == null && contentDescription == null &&
        preTapText == null && preTapContentDescription == null

/** Shared wording for the "N steps have no ID or label" warning, used identically by
 *  the step list, Copy Report, and the stop-recording log line - null when there's
 *  nothing to warn about. */
fun List<RecordedStep>.unlabeledStepWarning(): String? {
    val count = count { it.hasNoIdentity() }
    if (count == 0) return null
    return "⚠ $count steps have no ID or label. Replay can't find them reliably. " +
        "Add a contentDescription or resource ID to these controls."
}
