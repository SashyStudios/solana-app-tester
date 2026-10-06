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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.SavedFlow
import com.clockin.apptester.model.hasNoIdentity
import com.clockin.apptester.model.postTapDetail
import com.clockin.apptester.model.primaryLabel
import com.clockin.apptester.model.unlabeledStepWarning
import com.clockin.apptester.ui.theme.SashyColors
import com.clockin.apptester.ui.theme.SashyTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pure file I/O via Context - independent of whether the accessibility service
        // is connected yet (enabling it is a separate manual step in Settings).
        RecorderBridge.loadSavedFlows(this)
        setContent {
            SashyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SashyColors.StudioBlack) {
                    TesterScreen(
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
            "${index + 1}. $warning${step.primaryLabel()} | ${step.packageName}"
        }.joinToString("\n")
    }

/** Full plain-text report for "Copy report": header (target app, date/time, Android
 *  version), the recorded steps, and the full replay log - same data already on screen,
 *  just as text someone can paste into a bug report or a Discord message. */
private fun buildFullReportText(steps: List<RecordedStep>, log: List<String>): String {
    val now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
    val appPackage = steps.firstOrNull()?.packageName ?: "(none)"
    return buildString {
        appendLine("=== Solana App Tester Report ===")
        appendLine("App: $appPackage")
        appendLine("Date: $now")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
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
private fun TesterScreen(onOpenAccessibilitySettings: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val serviceConnected by RecorderBridge.isServiceConnected.collectAsState()
    val mode by RecorderBridge.mode.collectAsState()
    val steps by RecorderBridge.recordedSteps.collectAsState()
    val log by RecorderBridge.replayLog.collectAsState()
    val countdown by RecorderBridge.countdownSecondsRemaining.collectAsState()
    val savedFlows by RecorderBridge.savedFlows.collectAsState()
    var flowPendingDelete by remember { mutableStateOf<SavedFlow?>(null) }

    val activePackage = steps.firstOrNull()?.packageName

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
            enabled = serviceConnected && mode != RecorderMode.REPLAYING,
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

        Text(
            "Saved flows",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = SashyColors.White
        )
        if (savedFlows.isEmpty()) {
            Text("No saved flows yet", color = SashyColors.DimWhite)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                savedFlows.forEach { flow ->
                    SavedFlowRow(
                        flow = flow,
                        isActive = flow.packageName == activePackage,
                        enabled = mode == RecorderMode.IDLE,
                        onSelect = { RecorderBridge.selectSavedFlow(flow) },
                        onDeleteRequested = { flowPendingDelete = flow }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { RecorderBridge.startReplay() },
                enabled = serviceConnected && mode == RecorderMode.IDLE && steps.isNotEmpty(),
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
                    clipboardManager.setText(AnnotatedString(buildFullReportText(steps, log)))
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
                .weight(1f)
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
}

/** One row per saved flow - app name (falls back to the raw package name if
 *  PackageManager can't resolve a label, e.g. the app was uninstalled since saving),
 *  step count, saved time. Tapping it makes it the active flow (green highlight);
 *  "Delete" asks for confirmation before removing anything. */
@Composable
private fun SavedFlowRow(
    flow: SavedFlow,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val context = LocalContext.current
    val appLabel = remember(flow.packageName) { resolveAppLabel(context, flow.packageName) }
    val savedTime = remember(flow.savedAtEpochMillis) { formatSavedTime(flow.savedAtEpochMillis) }
    val borderColor = if (isActive) SashyColors.ElectricGreen else SashyColors.BorderGray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SashyColors.CardBlack, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                appLabel,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) SashyColors.ElectricGreen else SashyColors.White
            )
            Text(
                "${flow.steps.size} steps | saved $savedTime",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SashyColors.DimWhite
            )
        }
        TextButton(onClick = onDeleteRequested) {
            Text("Delete", color = SashyColors.ErrorRed)
        }
    }
}
