package com.clockin.mwatestapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clockin.mwatestapp.ui.theme.SashyColors
import com.clockin.mwatestapp.ui.theme.SashyTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {

    // Must be created before the Activity reaches STARTED - registers an activity result
    // launcher that the wallet-connect/approve intents round-trip through.
    private lateinit var activityResultSender: ActivityResultSender
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityResultSender = ActivityResultSender(this)

        setContent {
            SashyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SashyColors.StudioBlack) {
                    MainScreen(
                        viewModel = viewModel,
                        onConnect = { viewModel.connectWallet(activityResultSender) },
                        onSendTest = { viewModel.sendTestTransaction(activityResultSender) },
                        onSendLarge = { viewModel.sendLargeTransaction(activityResultSender) }
                    )
                }
            }
        }
    }
}

private fun messageColor(status: FlowStatus): Color = when (status) {
    FlowStatus.SUCCESS -> SashyColors.DeepGreen
    FlowStatus.FAILED, FlowStatus.NO_WALLET_FOUND -> SashyColors.ErrorRed
    else -> SashyColors.DimWhite
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onSendTest: () -> Unit,
    onSendLarge: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val busy = state.status == FlowStatus.CONNECTING || state.status == FlowStatus.AWAITING_APPROVAL
    val connected = state.walletAddress != null

    val skrButtonColors = ButtonDefaults.buttonColors(
        containerColor = SashyColors.SolanaPurple,
        contentColor = SashyColors.White,
        disabledContainerColor = SashyColors.BorderGray,
        disabledContentColor = SashyColors.DimWhite
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "MWA Test Target App",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SashyColors.White
        )
        Text(
            state.walletAddress?.let { "Connected: $it" } ?: "Not connected",
            color = SashyColors.DimWhite
        )

        Button(
            onClick = onConnect,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SashyColors.ElectricGreen,
                contentColor = SashyColors.PureBlack,
                disabledContainerColor = SashyColors.BorderGray,
                disabledContentColor = SashyColors.DimWhite
            )
        ) {
            Text("Connect Wallet")
        }
        Button(
            onClick = onSendTest,
            enabled = !busy && connected,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = skrButtonColors
        ) {
            Text("Send Test Transaction")
        }
        Button(
            onClick = onSendLarge,
            enabled = !busy && connected,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = skrButtonColors
        ) {
            Text("Send Large Transaction")
        }

        HorizontalDivider(color = SashyColors.BorderGray)

        if (busy) {
            CircularProgressIndicator(color = SashyColors.ElectricGreen)
        }

        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = messageColor(state.status)
        )

        state.lastSignature?.let {
            Text(
                "Last signature: $it",
                style = MaterialTheme.typography.bodySmall,
                color = SashyColors.DimWhite
            )
        }
    }
}
