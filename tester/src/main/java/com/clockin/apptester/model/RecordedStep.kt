package com.clockin.apptester.model

/**
 * Raw recorded click - no MWA awareness yet (that layers on top in a later build day).
 * resourceId is the primary match key on replay; text/className are fallbacks for
 * views without a resource id (common in some wallet-app dialogs).
 */
data class RecordedStep(
    val packageName: String,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?
)

/** Human-readable label, in the one precedence order used everywhere a step needs to
 *  be shown to a person - the status pill, the replay log, and the tester's step list. */
fun RecordedStep.displayLabel(): String = text ?: contentDescription ?: resourceId ?: "(unlabeled)"
