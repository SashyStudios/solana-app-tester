package com.clockin.apptester.model

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved recording for one target app. One of these per packageName, ever - see
 *  SavedFlowStore. */
data class SavedFlow(
    val packageName: String,
    val steps: List<RecordedStep>,
    val savedAtEpochMillis: Long
)

/**
 * Persists recordings as one JSON file per target package in the tester's own internal
 * storage (filesDir) - no database, no cloud, no multiple flows per app. Re-recording the
 * same app overwrites its file. formatVersion is written into every file so a future
 * format change can still tell an old file apart from a new one and migrate/reject it
 * deliberately instead of guessing.
 */
object SavedFlowStore {
    private const val FORMAT_VERSION = 1
    private const val FILE_PREFIX = "flow_"
    private const val FILE_SUFFIX = ".json"

    fun save(context: Context, packageName: String, steps: List<RecordedStep>) {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("packageName", packageName)
        root.put("savedAtEpochMillis", System.currentTimeMillis())
        val stepsArray = JSONArray()
        steps.forEach { stepsArray.put(it.toJson()) }
        root.put("steps", stepsArray)
        file(context, packageName).writeText(root.toString())
    }

    /** Skips (rather than crashes on) any file that fails to parse - a corrupt or
     *  unrecognized file just doesn't show up in the saved-flows list. */
    fun loadAll(context: Context): List<SavedFlow> {
        val files = context.filesDir
            .listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?: emptyArray()
        return files.mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.savedAtEpochMillis }
    }

    fun delete(context: Context, packageName: String) {
        file(context, packageName).delete()
    }

    /**
     * Picks the target app out of a recording's steps as the most common packageName
     * among them, after excluding the device's current home/launcher app and systemui -
     * resolved dynamically via PackageManager rather than hardcoded, since the launcher
     * package name varies by device/OEM. A single launcher tap at the very start (opening
     * the target app) would only ever contribute one step to that tally, so even without
     * this exclusion it would rarely out-vote a real flow's many target-app taps - this
     * exclusion exists to remove that edge case entirely for short recordings, not because
     * it was otherwise unsafe. It's still a frequency heuristic, not a guarantee: a flow
     * that bounces through several non-launcher screens before settling on the target
     * (e.g. an app-drawer search) could still be misread. Returns null only if every step
     * was excluded (e.g. a recording with nothing but launcher taps).
     */
    fun determineTargetPackage(context: Context, steps: List<RecordedStep>): String? {
        val homePackage = resolveHomePackage(context)
        val excluded = setOfNotNull(homePackage, "com.android.systemui", context.packageName)
        return steps.map { it.packageName }
            .filterNot { it in excluded }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    private fun resolveHomePackage(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    private fun file(context: Context, packageName: String): File =
        File(context.filesDir, "$FILE_PREFIX$packageName$FILE_SUFFIX")

    private fun parse(text: String): SavedFlow {
        val root = JSONObject(text)
        val packageName = root.getString("packageName")
        val savedAt = root.optLong("savedAtEpochMillis", 0L)
        val stepsArray = root.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArray.length()).map { i -> stepsArray.getJSONObject(i).toRecordedStep() }
        return SavedFlow(packageName, steps, savedAt)
    }

    private fun RecordedStep.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("packageName", packageName)
        obj.putNullable("className", className)
        obj.putNullable("resourceId", resourceId)
        obj.putNullable("text", text)
        obj.putNullable("contentDescription", contentDescription)
        obj.putNullable("preTapText", preTapText)
        obj.putNullable("preTapContentDescription", preTapContentDescription)
        return obj
    }

    private fun JSONObject.toRecordedStep(): RecordedStep = RecordedStep(
        packageName = getString("packageName"),
        className = optNullableString("className"),
        resourceId = optNullableString("resourceId"),
        text = optNullableString("text"),
        contentDescription = optNullableString("contentDescription"),
        preTapText = optNullableString("preTapText"),
        preTapContentDescription = optNullableString("preTapContentDescription")
    )

    /** Omits the key entirely rather than writing a JSON null - equally parseable,
     *  slightly more compact, and keeps optNullableString's "has(name)" check meaningful. */
    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
