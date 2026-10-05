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
