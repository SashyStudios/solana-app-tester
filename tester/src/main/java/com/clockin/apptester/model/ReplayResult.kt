package com.clockin.apptester.model

/** The outcome of the most recently finished replay - see RecorderBridge.lastResult.
 *  Doesn't know which saved flow (if any) was active during that replay; the "Last
 *  result" card resolves that separately from RecorderBridge.activeFlowId, the same way
 *  the saved-flows list's own green highlight does. */
sealed class ReplayResult {
    data class Passed(val totalSteps: Int) : ReplayResult()
    data class Failed(val stepNumber: Int, val totalSteps: Int, val explanation: String) : ReplayResult()
}
