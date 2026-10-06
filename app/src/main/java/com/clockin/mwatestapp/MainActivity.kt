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

    // Local to this screen, not the ViewModel - the three safe test-action buttons never
    // touch wallet/MWA state, so there's no reason to route them through MainViewModel.
    // Resets on process/Activity recreation, which is fine for a throwaway counter.
    private var pingCount = 0

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

        setUpSafeTestActions()
    }

    /**
     * Three buttons that never touch wallet/MWA state at all - a target the tester can
     * record/replay against without a devnet wallet connected. The planted demo bug (see
     * CLAUDE.md status) lives here: in the v2 build flavor only, btnCheck's id and label
     * change (btnCheck/"Check status" -> btnStatusCheck/"Run check"), simulating a
     * developer renaming a control between versions. v1 is unchanged either way.
     */
    private fun setUpSafeTestActions() {
        val btnCheck = findViewById<Button>(R.id.btnCheck)
        val btnPing = findViewById<Button>(R.id.btnPing)
        val btnReset = findViewById<Button>(R.id.btnReset)
        val resultText = findViewById<TextView>(R.id.txtSafeResult)
        val buildLabel = findViewById<TextView>(R.id.txtBuildLabel)

        if (BuildConfig.FLAVOR == "v2") {
            btnCheck.id = R.id.btnStatusCheck
            btnCheck.text = "Run check"
        }

        btnPing.setOnClickListener {
            pingCount++
            resultText.text = "Ping #$pingCount sent"
        }
        btnCheck.setOnClickListener {
            resultText.text = "Status: OK ($pingCount pings)"
        }
        btnReset.setOnClickListener {
            pingCount = 0
            resultText.text = "Counter reset"
        }

        buildLabel.text = "build: ${BuildConfig.FLAVOR}"
    }

    private fun messageColor(status: FlowStatus): Int = when (status) {
        FlowStatus.SUCCESS -> ContextCompat.getColor(this, R.color.deep_green)
        FlowStatus.FAILED, FlowStatus.NO_WALLET_FOUND -> ContextCompat.getColor(this, R.color.error_red)
        else -> ContextCompat.getColor(this, R.color.dim_white)
    }
}
