package com.clockin.mwatestapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Must be created before the Activity reaches STARTED - registers an activity result
    // launcher that the wallet-connect/approve intents round-trip through.
    private lateinit var activityResultSender: ActivityResultSender
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityResultSender = ActivityResultSender(this)
        setContentView(R.layout.activity_main)

        val walletStatusText = findViewById<TextView>(R.id.tvWalletStatus)
        val connectButton = findViewById<Button>(R.id.btnConnectWallet)
        val sendTestButton = findViewById<Button>(R.id.btnSendTest)
        val sendLargeButton = findViewById<Button>(R.id.btnSendLarge)
        val progressBusy = findViewById<ProgressBar>(R.id.progressBusy)
        val statusText = findViewById<TextView>(R.id.statusText)
        val lastSignatureText = findViewById<TextView>(R.id.tvLastSignature)

        connectButton.setOnClickListener { viewModel.connectWallet(activityResultSender) }
        sendTestButton.setOnClickListener { viewModel.sendTestTransaction(activityResultSender) }
        sendLargeButton.setOnClickListener { viewModel.sendLargeTransaction(activityResultSender) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val busy = state.status == FlowStatus.CONNECTING || state.status == FlowStatus.AWAITING_APPROVAL
                    val connected = state.walletAddress != null

                    walletStatusText.text = state.walletAddress?.let { "Connected: $it" } ?: "Not connected"
                    connectButton.isEnabled = !busy
                    sendTestButton.isEnabled = !busy && connected
                    sendLargeButton.isEnabled = !busy && connected
                    progressBusy.visibility = if (busy) View.VISIBLE else View.GONE

                    statusText.text = state.message
                    statusText.setTextColor(messageColor(state.status))

                    if (state.lastSignature != null) {
                        lastSignatureText.visibility = View.VISIBLE
                        lastSignatureText.text = "Last signature: ${state.lastSignature}"
                    } else {
                        lastSignatureText.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun messageColor(status: FlowStatus): Int = when (status) {
        FlowStatus.SUCCESS -> ContextCompat.getColor(this, R.color.deep_green)
        FlowStatus.FAILED, FlowStatus.NO_WALLET_FOUND -> ContextCompat.getColor(this, R.color.error_red)
        else -> ContextCompat.getColor(this, R.color.dim_white)
    }
}
