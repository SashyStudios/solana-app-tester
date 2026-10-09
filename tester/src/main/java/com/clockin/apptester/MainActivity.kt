package com.clockin.apptester

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.ReplayResult
import com.clockin.apptester.model.SavedFlow
import com.clockin.apptester.model.hasNoIdentity
import com.clockin.apptester.model.postTapDetail
import com.clockin.apptester.model.primaryLabel
import com.clockin.apptester.model.unlabeledStepWarning
import com.clockin.apptester.solana.DevnetConfig
import com.clockin.apptester.solana.WalletConnection
import com.clockin.apptester.solana.WalletConnector
import com.clockin.apptester.solana.WalletMessage
import com.clockin.apptester.ui.theme.SashyColors
import com.clockin.apptester.ui.theme.SashyTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Must be constructed in onCreate, before the activity is resumed - it registers an
     *  activity-result launcher, which Android only allows before that point. */
    private lateinit var activityResultSender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityResultSender = ActivityResultSender(this)
        // Pure file I/O via Context - independent of whether the accessibility service
        // is connected yet (enabling it is a separate manual step in Settings).
        RecorderBridge.loadSavedFlows(this)
        setContent {
            SashyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SashyColors.StudioBlack) {
                    TesterScreen(
                        sender = activityResultSender,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                }
            }
        }
    }
}

private fun resolveAppLabel(context: Context, packageName: String): String {
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/** "<App name> #N" by default, or the flow's own stored name if it's been renamed - the
 *  single source of truth for what a saved flow is called, used by the row, the Last
 *  result card, and the Copy report header. */
private fun displayNameFor(context: Context, flow: SavedFlow, ordinal: Int): String =
    flow.name ?: "${resolveAppLabel(context, flow.packageName)} #$ordinal"

private fun formatSavedTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "unknown time"
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun buildStepsReportText(steps: List<RecordedStep>): String =
    if (steps.isEmpty()) {
        "(no recorded steps)"
    } else {
        steps.mapIndexed { index, step ->
            val warning = if (step.hasNoIdentity()) "⚠ " else ""
            val postTap = step.postTapDetail()
            val suffix = if (postTap != null) " (shows \"$postTap\" after tap)" else ""
            "${index + 1}. $warning${step.primaryLabel()} | ${step.packageName}$suffix"
        }.joinToString("\n")
    }

/** One saved flow plus its display ordinal within its own app's group - #1 is that app's
 *  oldest flow, increasing with each later recording, independent of how the list is
 *  sorted for display. */
private data class DisplayFlow(val flow: SavedFlow, val ordinal: Int)

/** Groups saved flows by app (so one app's flows are never interleaved with another's),
 *  orders the groups by whichever app was most recently recorded, and within each group
 *  shows newest first - "every flow, newest first, grouped by app." */
private fun buildFlowDisplayList(savedFlows: List<SavedFlow>): List<DisplayFlow> {
    val byPackage = savedFlows.groupBy { it.packageName }
    val groupOrder = byPackage.values.sortedByDescending { flows -> flows.maxOf { it.savedAtEpochMillis } }
    return groupOrder.flatMap { flows ->
        val ordinalById = flows.sortedBy { it.savedAtEpochMillis }
            .withIndex()
            .associate { (i, f) -> f.id to (i + 1) }
        flows.sortedByDescending { it.savedAtEpochMillis }
            .map { f -> DisplayFlow(f, ordinalById.getValue(f.id)) }
    }
}

/** Full plain-text report for "Copy report": header (the active flow's name if there is
 *  one, date/time, Android version), the recorded steps, and the full replay log - same
 *  data already on screen, just as text someone can paste into a bug report or a Discord
 *  message. flowName is null when there's no active saved flow (e.g. an unsaved
 *  recording), falling back to the raw target package. */
private fun buildFullReportText(steps: List<RecordedStep>, log: List<String>, flowName: String?): String {
    val now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
    val appLabel = flowName ?: steps.firstOrNull()?.packageName ?: "(none)"
    return buildString {
        appendLine("=== Solana App Tester Report ===")
        appendLine("App: $appLabel")
        appendLine("Date: $now")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Note: the tool reports differences it can observe on screen. It doesn't know what code changed.")
        appendLine()
        appendLine("-- Recorded steps (${steps.size}) --")
        appendLine(buildStepsReportText(steps))
        steps.unlabeledStepWarning()?.let { appendLine(it) }
        appendLine()
        appendLine("-- Replay log --")
        append(if (log.isEmpty()) "(no replay yet)" else log.joinToString("\n"))
    }
}

@Composable
private fun TesterScreen(sender: ActivityResultSender, onOpenAccessibilitySettings: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val serviceConnected by RecorderBridge.isServiceConnected.collectAsState()
    val mode by RecorderBridge.mode.collectAsState()
    val steps by RecorderBridge.recordedSteps.collectAsState()
    val log by RecorderBridge.replayLog.collectAsState()
    val unrecordedTaps by RecorderBridge.unrecordedTaps.collectAsState()
    val countdown by RecorderBridge.countdownSecondsRemaining.collectAsState()
    val savedFlows by RecorderBridge.savedFlows.collectAsState()
    val activeFlowId by RecorderBridge.activeFlowId.collectAsState()
    val lastResult by RecorderBridge.lastResult.collectAsState()
    val walletConnection by WalletConnector.connection.collectAsState()
    val walletBusy by WalletConnector.requestInProgress.collectAsState()
    val walletMessage by WalletConnector.message.collectAsState()
    var flowPendingDelete by remember { mutableStateOf<SavedFlow?>(null) }
    var flowPendingRename by remember { mutableStateOf<SavedFlow?>(null) }

    val displayFlows = remember(savedFlows) { buildFlowDisplayList(savedFlows) }
    val activeDisplay = displayFlows.firstOrNull { it.flow.id == activeFlowId }
    val activeFlowName = activeDisplay?.let { displayNameFor(context, it.flow, it.ordinal) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            // Whole-screen scroll - needed once the saved-flows list (capped below, but
            // still as tall as ~3 rows) plus everything else can exceed the viewport.
            // Every list below has its own fixed height and scrolls internally instead
            // of using weight(1f), since weight doesn't work against a scrolling parent
            // (it measures children with bounded height, which a scrollable Column can't
            // offer - it measures its content at its natural/unbounded height instead).
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Solana App Tester",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SashyColors.White
        )
        Text(
            if (serviceConnected) "Accessibility service: connected"
            else "Accessibility service: not enabled",
            color = SashyColors.DimWhite
        )

        if (!serviceConnected) {
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SashyColors.BorderGray,
                    contentColor = SashyColors.White
                )
            ) {
                Text("Enable Accessibility Service")
            }
        }

        // Start/Cancel/Stop is one button whose label and action follow the current mode -
        // a tap during the countdown cancels it, mirroring what volume-down does. Touch
        // exploration is never requested anywhere in this path - see
        // RecorderBridge.startRecordingCountdown.
        val (startButtonLabel, startButtonAction) = when (mode) {
            RecorderMode.RECORDING -> "Stop Recording" to { RecorderBridge.stopRecordingManually() }
            RecorderMode.COUNTDOWN -> "Cancel Countdown" to { RecorderBridge.cancelCountdown() }
            else -> "Record (normal touch)" to { RecorderBridge.startRecordingCountdown() }
        }

        Button(
            onClick = startButtonAction,
            // Also disabled while a wallet request is open: a wallet window is in the
            // foreground then, and nothing else should start against it.
            enabled = serviceConnected && mode != RecorderMode.REPLAYING && !walletBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SashyColors.ElectricGreen,
                contentColor = SashyColors.PureBlack,
                disabledContainerColor = SashyColors.BorderGray,
                disabledContentColor = SashyColors.DimWhite
            )
        ) {
            Text(startButtonLabel)
        }

        WalletCard(
            connection = walletConnection,
            busy = walletBusy,
            message = walletMessage,
            connectEnabled = mode == RecorderMode.IDLE && !walletBusy,
            onConnect = { scope.launch { WalletConnector.connect(sender) } },
            onDisconnect = { scope.launch { WalletConnector.disconnect(sender) } }
        )

        lastResult?.let { result ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
                    .border(1.dp, SashyColors.BorderGray, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Last result",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SashyColors.White
                )
                when (result) {
                    is ReplayResult.Passed -> {
                        val appSuffix = activeFlowName?.let { " ($it)" } ?: ""
                        Text(
                            "Replay passed: ${result.totalSteps} of ${result.totalSteps} steps$appSuffix",
                            fontFamily = FontFamily.Monospace,
                            color = SashyColors.ElectricGreen
                        )
                    }
                    is ReplayResult.FinishedWithSkips -> {
                        // Deliberately neutral, never green: at least one step's window was
                        // protected and skipped unread, so the flow wasn't verified end to
                        // end. See PackageGuard.
                        val appSuffix = activeFlowName?.let { " ($it)" } ?: ""
                        Text(
                            "Replay finished with skipped steps$appSuffix",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = SashyColors.DimWhite
                        )
                        Text(
                            "${result.completedSteps} ran, ${result.skippedSteps} skipped, " +
                                "of ${result.totalSteps} steps",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = SashyColors.DimWhite
                        )
                    }
                    is ReplayResult.Failed -> {
                        // explanation's own first line already reads "Step N/M FAILED:
                        // expected ..., not found." - shown bold, the rest (rename
                        // assessment, other on-screen elements) below it, same color.
                        val lines = result.explanation.split("\n")
                        Text(
                            lines.firstOrNull() ?: "Step ${result.stepNumber} of ${result.totalSteps} failed",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = SashyColors.ErrorRed
                        )
                        val rest = lines.drop(1).joinToString("\n")
                        if (rest.isNotEmpty()) {
                            Text(
                                rest,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = SashyColors.ErrorRed
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Saved flows",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = SashyColors.White
        )
        if (displayFlows.isEmpty()) {
            Text("No saved flows yet", color = SashyColors.DimWhite)
        } else {
            // Fixed height (~3 rows) with its own internal scroll, so a long list scrolls
            // in place instead of pushing the rest of the screen down indefinitely.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayFlows) { display ->
                    SavedFlowRow(
                        flow = display.flow,
                        ordinal = display.ordinal,
                        isActive = display.flow.id == activeFlowId,
                        enabled = mode == RecorderMode.IDLE,
                        onSelect = { RecorderBridge.selectSavedFlow(display.flow) },
                        onRenameRequested = { flowPendingRename = display.flow },
                        onDeleteRequested = { flowPendingDelete = display.flow }
                    )
                }
            }
        }

        if (mode == RecorderMode.COUNTDOWN) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    (countdown ?: 0).toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = SashyColors.ElectricGreen
                )
            }
            Text(
                "Touch exploration is off - switch to the target app now with normal touches.",
                color = SashyColors.DimWhite
            )
        }

        if (mode == RecorderMode.RECORDING) {
            Text(
                "Recording. Tap normally. Volume-down to stop.",
                fontWeight = FontWeight.Bold,
                color = SashyColors.ElectricGreen
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recorded steps: ${steps.size}", color = SashyColors.DimWhite)
            if (steps.isNotEmpty()) {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(buildStepsReportText(steps)))
                    Toast.makeText(context, "Steps copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy", color = SashyColors.ElectricGreen)
                }
            }
        }

        if (steps.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
                    .border(1.dp, SashyColors.BorderGray, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                itemsIndexed(steps) { index, step ->
                    val postTap = step.postTapDetail()
                    val suffix = if (postTap != null) " (shows \"$postTap\" after tap)" else ""
                    val warning = if (step.hasNoIdentity()) "⚠ " else ""
                    Text(
                        "${index + 1}. $warning${step.primaryLabel()} | ${step.packageName}$suffix",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = SashyColors.ElectricGreen
                    )
                }
            }
            steps.unlabeledStepWarning()?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = SashyColors.ErrorRed
                )
            }
        }
        RecorderBridge.unrecordedTapsMessage(unrecordedTaps)?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = SashyColors.DimWhite
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { RecorderBridge.startReplay() },
                enabled = serviceConnected && mode == RecorderMode.IDLE &&
                    steps.isNotEmpty() && !walletBusy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SashyColors.ElectricGreen,
                    contentColor = SashyColors.PureBlack,
                    disabledContainerColor = SashyColors.BorderGray,
                    disabledContentColor = SashyColors.DimWhite
                )
            ) {
                Text("Replay")
            }

            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(buildFullReportText(steps, log, activeFlowName)))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SashyColors.BorderGray,
                    contentColor = SashyColors.White
                )
            ) {
                Text("Copy Report")
            }
        }

        HorizontalDivider(color = SashyColors.BorderGray)

        Text(
            "Replay log",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = SashyColors.White
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // Was weight(1f) - doesn't work once the parent Column scrolls (a
                // scrolling parent measures its content at natural height, so weight has
                // no bounded space to divide). Fixed height with its own internal scroll
                // instead, same pattern as the saved-flows and recorded-steps lists.
                .height(200.dp)
                .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
                .border(1.dp, SashyColors.BorderGray, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = SashyColors.ElectricGreen
                )
            }
        }
    }

    flowPendingDelete?.let { flow ->
        val label = remember(flow.packageName) { resolveAppLabel(context, flow.packageName) }
        AlertDialog(
            onDismissRequest = { flowPendingDelete = null },
            title = { Text("Delete saved flow?") },
            text = { Text("This removes the saved flow for $label. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    RecorderBridge.deleteSavedFlow(context, flow)
                    flowPendingDelete = null
                }) {
                    Text("Delete", color = SashyColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { flowPendingDelete = null }) {
                    Text("Cancel", color = SashyColors.White)
                }
            },
            containerColor = SashyColors.CardBlack,
            titleContentColor = SashyColors.White,
            textContentColor = SashyColors.DimWhite
        )
    }

    flowPendingRename?.let { flow ->
        val display = displayFlows.firstOrNull { it.flow.id == flow.id }
        val ordinal = display?.ordinal ?: 1
        var nameInput by remember(flow.id) { mutableStateOf(displayNameFor(context, flow, ordinal)) }
        AlertDialog(
            onDismissRequest = { flowPendingRename = null },
            title = { Text("Rename flow") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SashyColors.White,
                        unfocusedTextColor = SashyColors.White,
                        focusedContainerColor = SashyColors.CardBlack,
                        unfocusedContainerColor = SashyColors.CardBlack,
                        focusedBorderColor = SashyColors.ElectricGreen,
                        unfocusedBorderColor = SashyColors.BorderGray,
                        cursorColor = SashyColors.ElectricGreen
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RecorderBridge.renameSavedFlow(context, flow, nameInput)
                    flowPendingRename = null
                }) {
                    Text("Save", color = SashyColors.ElectricGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { flowPendingRename = null }) {
                    Text("Cancel", color = SashyColors.White)
                }
            },
            containerColor = SashyColors.CardBlack,
            titleContentColor = SashyColors.White,
            textContentColor = SashyColors.DimWhite
        )
    }
}

/**
 * Stage 1 of the MWA feature: authorize against devnet and show what connected. No
 * transactions, no signing - those come later. The cluster is on screen as a badge rather
 * than implied, because "which network am I on" is the one thing nobody should have to
 * infer from a wallet screen.
 *
 * Nothing here ever drives the wallet app itself: the person taps approve in the wallet by
 * hand. The accessibility service is not involved in this card at all.
 */
@Composable
private fun WalletCard(
    connection: WalletConnection?,
    busy: Boolean,
    message: WalletMessage?,
    connectEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
            .border(1.dp, SashyColors.BorderGray, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Wallet (devnet)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SashyColors.White
            )
            Text(
                DevnetConfig.CLUSTER_LABEL,
                modifier = Modifier
                    .background(SashyColors.SolanaPurple, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = SashyColors.White
            )
        }

        if (connection == null) {
            Text(
                if (busy) "Waiting for the wallet. Approve the request by hand."
                else "Not connected.",
                fontSize = 12.sp,
                color = SashyColors.DimWhite
            )
        } else {
            Text("Address", fontSize = 11.sp, color = SashyColors.DimWhite)
            // Full address, never truncated - a partially shown key is useless for
            // checking you're on the wallet you meant to be on.
            Text(
                connection.address,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = SashyColors.ElectricGreen
            )
            Text(
                connection.label?.let { "Wallet label: $it" } ?: "Wallet label: (none given)",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = SashyColors.DimWhite
            )
            if (!DevnetConfig.isExpectedTestWallet(connection.address)) {
                Text(
                    "Not the configured test wallet. Disconnect before doing anything else.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SashyColors.PureBlack, RoundedCornerShape(8.dp))
                        .border(1.dp, SashyColors.ErrorRed, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = SashyColors.ErrorRed
                )
            }
        }

        message?.let {
            Text(
                it.text,
                fontSize = 12.sp,
                color = if (it.isError) SashyColors.ErrorRed else SashyColors.DimWhite
            )
        }

        if (connection == null) {
            Button(
                onClick = onConnect,
                enabled = connectEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    // Purple is the brand's Solana/crypto action colour - keeps this
                    // visually distinct from the tool's own green Record/Replay actions.
                    containerColor = SashyColors.SolanaPurple,
                    contentColor = SashyColors.White,
                    disabledContainerColor = SashyColors.BorderGray,
                    disabledContentColor = SashyColors.DimWhite
                )
            ) {
                Text("Connect wallet (devnet)")
            }
        } else {
            Button(
                onClick = onDisconnect,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SashyColors.BorderGray,
                    contentColor = SashyColors.White,
                    disabledContainerColor = SashyColors.BorderGray,
                    disabledContentColor = SashyColors.DimWhite
                )
            ) {
                Text("Disconnect")
            }
        }
    }
}

/** One row per saved flow, stacked top to bottom so the name and step/time line each get
 *  the card's full width instead of squeezing into a narrow column beside Rename/Delete:
 *  the name (bold, green when active), a dimmed "<N> steps | <date time>" line, then a
 *  compact action row. <name> is the flow's stored name if it's been renamed, else the
 *  same "<App name> #N" default as before (see displayNameFor; #N is this flow's ordinal
 *  within its own app's group, see buildFlowDisplayList). Tapping anywhere on the card
 *  (outside the two buttons) makes it the active flow (green border); "Rename" opens a
 *  text dialog pre-filled with the current name; "Delete" asks for confirmation before
 *  removing anything - every other flow, including other recordings of the same app, is
 *  left alone by either action. */
@Composable
private fun SavedFlowRow(
    flow: SavedFlow,
    ordinal: Int,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRenameRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val context = LocalContext.current
    val displayName = remember(flow.name, flow.packageName, ordinal) { displayNameFor(context, flow, ordinal) }
    val savedTime = remember(flow.savedAtEpochMillis) { formatSavedTime(flow.savedAtEpochMillis) }
    val borderColor = if (isActive) SashyColors.ElectricGreen else SashyColors.BorderGray

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            displayName,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isActive) SashyColors.ElectricGreen else SashyColors.White
        )
        Text(
            "${flow.steps.size} steps | $savedTime",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = SashyColors.DimWhite
        )
        Row {
            TextButton(onClick = onRenameRequested) {
                Text("Rename", fontSize = 12.sp, color = SashyColors.ElectricGreen)
            }
            TextButton(onClick = onDeleteRequested) {
                Text("Delete", fontSize = 12.sp, color = SashyColors.ErrorRed)
            }
        }
    }
}
