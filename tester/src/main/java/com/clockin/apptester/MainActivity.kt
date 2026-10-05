package com.clockin.apptester

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clockin.apptester.ui.theme.SashyColors
import com.clockin.apptester.ui.theme.SashyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@Composable
private fun TesterScreen(onOpenAccessibilitySettings: () -> Unit) {
    val serviceConnected by RecorderBridge.isServiceConnected.collectAsState()
    val mode by RecorderBridge.mode.collectAsState()
    val steps by RecorderBridge.recordedSteps.collectAsState()
    val log by RecorderBridge.replayLog.collectAsState()
    val countdown by RecorderBridge.countdownSecondsRemaining.collectAsState()

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
        // a tap during the countdown cancels it, mirroring what volume-down does.
        val (startButtonLabel, startButtonAction) = when (mode) {
            RecorderMode.RECORDING -> "Stop Recording" to { RecorderBridge.stopRecordingManually() }
            RecorderMode.COUNTDOWN -> "Cancel Countdown" to { RecorderBridge.cancelCountdown() }
            else -> "Start Recording" to { RecorderBridge.startRecordingCountdown() }
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
                "Recording. Two-finger tap to click. Volume-down to stop.",
                fontWeight = FontWeight.Bold,
                color = SashyColors.ElectricGreen
            )
        }

        Text("Recorded steps: ${steps.size}", color = SashyColors.DimWhite)

        Button(
            onClick = { RecorderBridge.startReplay() },
            enabled = serviceConnected && mode == RecorderMode.IDLE && steps.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
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
}
