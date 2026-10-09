package com.clockin.apptester.solana

import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.ReplayResult
import com.clockin.apptester.model.primaryLabel
import java.security.MessageDigest

/**
 * Builds the one canonical string a replay result is hashed from, and that hash.
 *
 * Only the hash ever goes on chain. Deliberately excluded from the canonical string:
 * the date/time, any signature, and all device info - so the same flow with the same
 * outcome always produces the same hash, on any device, at any time. That repeatability
 * is the point: a hash that moved with the clock would prove nothing about the result.
 *
 * The canonical string itself is shown in full in the UI and included in "Copy proof",
 * so anyone can recompute the hash by hand and check it. It does contain the flow name,
 * package names and step labels - it stays on the device, and only its hash is published.
 */
object ReportHash {

    /**
     * Line order is fixed and must never be reordered, or previously published hashes
     * stop matching the reports they were made from.
     */
    fun canonicalString(
        flowName: String,
        targetPackage: String,
        steps: List<RecordedStep>,
        result: ReplayResult
    ): String = buildString {
        appendLine("flow: $flowName")
        appendLine("app: $targetPackage")
        steps.forEachIndexed { index, step ->
            appendLine("${index + 1}|${step.primaryLabel()}|${step.packageName}")
        }
        append("result: ${resultLine(result)}")
    }

    /** "passed 3/3", "failed at step 2 of 3", or the skipped-step variant. */
    fun resultLine(result: ReplayResult): String = when (result) {
        is ReplayResult.Passed -> "passed ${result.totalSteps}/${result.totalSteps}"
        is ReplayResult.Failed -> "failed at step ${result.stepNumber} of ${result.totalSteps}"
        is ReplayResult.FinishedWithSkips ->
            "finished ${result.completedSteps}/${result.totalSteps} with ${result.skippedSteps} skipped"
    }

    /** Lowercase hex SHA-256 of the canonical string's UTF-8 bytes - this is the memo. */
    fun sha256Hex(canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
