package com.clockin.apptester.model

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved recording. Multiple of these can exist for the same packageName - every
 * recording that stops gets its own file, nothing is ever overwritten. id is a stable
 * identity ("packageName_savedAtEpochMillis") derived from JSON content rather than the
 * filename, so it means the same thing whether the backing file uses the new per-recording
 * naming or the old one-file-per-package naming (see SavedFlowStore). fileName is the
 * actual file on disk this flow was loaded from/written to - needed by delete() and
 * rename(), otherwise opaque to callers. name is null unless the user has explicitly
 * renamed this flow - a file without one (every file saved before renaming existed, or
 * one never renamed since) is not an error, callers show the same "<App name> #N" default
 * they always have (see MainActivity's displayNameFor).
 */
data class SavedFlow(
    val id: String,
    val packageName: String,
    val steps: List<RecordedStep>,
    val savedAtEpochMillis: Long,
    val fileName: String,
    val name: String? = null
)

/**
 * Persists recordings as JSON files in the tester's own internal storage (filesDir) - no
 * database, no cloud. Every recording that stops with at least one step becomes its own
 * file (flow_<packageName>_<savedAtEpochMillis>.json) and nothing already on disk is ever
 * replaced or auto-deleted - only an explicit per-flow delete() removes a file. A file
 * written under the old one-file-per-package naming (flow_<packageName>.json, from before
 * multiple flows per app existed) still loads - loadAll() doesn't care what a file is
 * named, only what's inside it. formatVersion is written into every file so a future
 * format change can still tell an old file apart from a new one and migrate/reject it
 * deliberately instead of guessing.
 */
object SavedFlowStore {
    private const val FORMAT_VERSION = 1
    private const val FILE_PREFIX = "flow_"
    private const val FILE_SUFFIX = ".json"

    /** Always creates a new file - never overwrites an existing one, regardless of how
     *  many flows already exist for this packageName. */
    fun save(context: Context, packageName: String, steps: List<RecordedStep>): SavedFlow {
        val savedAt = System.currentTimeMillis()
        val fileName = "$FILE_PREFIX${packageName}_$savedAt$FILE_SUFFIX"
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("packageName", packageName)
        root.put("savedAtEpochMillis", savedAt)
        val stepsArray = JSONArray()
        steps.forEach { stepsArray.put(it.toJson()) }
        root.put("steps", stepsArray)
        File(context.filesDir, fileName).writeText(root.toString())
        return SavedFlow(id(packageName, savedAt), packageName, steps, savedAt, fileName)
    }

    /** Skips (rather than crashes on) any file that fails to parse - a corrupt or
     *  unrecognized file just doesn't show up in the saved-flows list. */
    fun loadAll(context: Context): List<SavedFlow> {
        val files = context.filesDir
            .listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?: emptyArray()
        return files.mapNotNull { f -> runCatching { parse(f.name, f.readText()) }.getOrNull() }
            .sortedByDescending { it.savedAtEpochMillis }
    }

    /** Deletes exactly the file this flow was loaded from - not derived from packageName
     *  alone, since several flows can share one. Never called except by an explicit,
     *  user-confirmed per-row delete. */
    fun delete(context: Context, flow: SavedFlow) {
        File(context.filesDir, flow.fileName).delete()
    }

    /** Rewrites this flow's existing file with the same content plus a new name - the
     *  one case where an existing flow file is deliberately overwritten (save() never
     *  overwrites; this is a rename of something already saved, not a new recording).
     *  A blank name (after trimming) clears back to null, which falls back to the
     *  computed default display name. */
    fun rename(context: Context, flow: SavedFlow, newName: String): SavedFlow {
        val trimmed = newName.trim()
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("packageName", flow.packageName)
        root.put("savedAtEpochMillis", flow.savedAtEpochMillis)
        root.putNullable("name", trimmed.ifEmpty { null })
        val stepsArray = JSONArray()
        flow.steps.forEach { stepsArray.put(it.toJson()) }
        root.put("steps", stepsArray)
        File(context.filesDir, flow.fileName).writeText(root.toString())
        return flow.copy(name = trimmed.ifEmpty { null })
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

    private fun id(packageName: String, savedAtEpochMillis: Long): String = "${packageName}_$savedAtEpochMillis"

    private fun parse(fileName: String, text: String): SavedFlow {
        val root = JSONObject(text)
        val packageName = root.getString("packageName")
        val savedAt = root.optLong("savedAtEpochMillis", 0L)
        val stepsArray = root.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsArray.length()).map { i -> stepsArray.getJSONObject(i).toRecordedStep() }
        // Absent in every file saved before renaming existed, and in any file never
        // renamed since - not an error, just means "use the computed default name."
        val name = if (root.has("name") && !root.isNull("name")) root.getString("name") else null
        return SavedFlow(id(packageName, savedAt), packageName, steps, savedAt, fileName, name)
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
        if (labelRemoved) obj.put("labelRemoved", true)
        return obj
    }

    private fun JSONObject.toRecordedStep(): RecordedStep = RecordedStep(
        packageName = getString("packageName"),
        className = optNullableString("className"),
        resourceId = optNullableString("resourceId")?.takeIf { it.isNotEmpty() },
        text = optNullableString("text"),
        contentDescription = optNullableString("contentDescription"),
        preTapText = optNullableString("preTapText"),
        preTapContentDescription = optNullableString("preTapContentDescription"),
        labelRemoved = optBoolean("labelRemoved", false)
    )

    /** Omits the key entirely rather than writing a JSON null - equally parseable,
     *  slightly more compact, and keeps optNullableString's "has(name)" check meaningful. */
    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value != null) put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
