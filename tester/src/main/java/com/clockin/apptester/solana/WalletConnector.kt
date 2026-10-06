package com.clockin.apptester.solana

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.base.Base58

/** What the wallet told us about the authorized account. label is null when the wallet
 *  doesn't supply one - it's optional in the MWA protocol, so it's never invented here. */
data class WalletConnection(val address: String, val label: String?)

/** isError drives the colour the UI shows this in, so a timeout or refusal can't end up
 *  rendered as quiet grey status text next to a success. */
data class WalletMessage(val text: String, val isError: Boolean)

/**
 * Stage 1 of the MWA feature: authorize against devnet and read back the account address.
 * No RPC calls, no transaction building, no signing.
 *
 * Nothing here ever retries. A failed or cancelled request ends, reports what happened, and
 * waits for the person to tap Connect again - an automatic retry would re-prompt the wallet
 * for an approval nobody asked for a second time.
 */
object WalletConnector {

    private const val REQUEST_TIMEOUT_MS = 30_000L

    /** Names this app separately from the demo target app in the wallet's authorization
     *  list - both appear there, and confusing the two mid-demo would be easy. */
    private val identity = ConnectionIdentity(
        identityUri = Uri.parse("https://clockin.hackathon/tester"),
        iconUri = Uri.parse("favicon.ico"),
        identityName = "Solana App Tester"
    )

    private val adapter = MobileWalletAdapter(identity).apply {
        // Also the library default; set explicitly so the cluster is stated in code, not assumed.
        blockchain = DevnetConfig.CLUSTER
    }

    private val _connection = MutableStateFlow<WalletConnection?>(null)
    val connection: StateFlow<WalletConnection?> = _connection.asStateFlow()

    /** True from the moment a request starts until it resolves. The UI disables Record,
     *  Replay and Connect while it's true, so nothing else can run against a foreground
     *  wallet window. */
    private val _requestInProgress = MutableStateFlow(false)
    val requestInProgress: StateFlow<Boolean> = _requestInProgress.asStateFlow()

    /** Last plain-language outcome, or null when there's nothing to say. */
    private val _message = MutableStateFlow<WalletMessage?>(null)
    val message: StateFlow<WalletMessage?> = _message.asStateFlow()

    suspend fun connect(sender: ActivityResultSender) {
        if (_requestInProgress.value) return
        DevnetConfig.requireDevnet(adapter.blockchain)

        _requestInProgress.value = true
        _message.value = null
        try {
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { adapter.connect(sender) }
            _message.value = when (result) {
                null -> WalletMessage(TIMEOUT_MESSAGE, isError = true)
                is TransactionResult.Success -> {
                    val account = result.authResult.accounts.first()
                    _connection.value = WalletConnection(
                        address = Base58.encode(account.publicKey),
                        label = account.accountLabel
                    )
                    WalletMessage("Connected on ${DevnetConfig.CLUSTER.fullName}.", isError = false)
                }
                is TransactionResult.NoWalletFound -> WalletMessage(NO_WALLET_MESSAGE, isError = true)
                is TransactionResult.Failure -> WalletMessage(plainFailureMessage(result.message), isError = true)
            }
        } finally {
            _requestInProgress.value = false
        }
    }

    /**
     * Deauthorizes with the wallet and drops the auth token. Local state is cleared either
     * way: if the deauthorize call itself failed, the wallet may still hold the
     * authorization, so the failure is reported rather than papered over - but this app
     * stops treating itself as connected regardless.
     */
    suspend fun disconnect(sender: ActivityResultSender) {
        if (_requestInProgress.value) return

        _requestInProgress.value = true
        _message.value = null
        try {
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { adapter.disconnect(sender) }
            _message.value = when (result) {
                null -> WalletMessage(TIMEOUT_MESSAGE, isError = true)
                is TransactionResult.Success -> WalletMessage("Disconnected.", isError = false)
                is TransactionResult.NoWalletFound -> WalletMessage(NO_WALLET_MESSAGE, isError = true)
                is TransactionResult.Failure -> WalletMessage(
                    "Disconnected locally, but the wallet didn't confirm: ${result.message}",
                    isError = true
                )
            }
        } finally {
            adapter.authToken = null
            _connection.value = null
            _requestInProgress.value = false
        }
    }

    /**
     * Only calls a request "cancelled" when the underlying message actually says so - a
     * cancel arrives as RESULT_CANCELED ("Request was interrupted") or as the protocol's
     * ERROR_NOT_SIGNED ("User did not authorize signing"). Anything else is passed through
     * verbatim rather than guessed at, so the UI never mislabels a real failure as a
     * deliberate cancel.
     */
    private fun plainFailureMessage(raw: String): String {
        val lower = raw.lowercase()
        val cancelled = lower.contains("interrupted") ||
            lower.contains("cancel") ||
            lower.contains("did not authorize")
        return if (cancelled) CANCELLED_MESSAGE else "Wallet request failed: $raw"
    }

    const val TIMEOUT_MESSAGE =
        "No response from the wallet. If you use Phantom, check that Testnet Mode is set to Solana Devnet."
    private const val NO_WALLET_MESSAGE =
        "No compatible wallet app found on this device."
    private const val CANCELLED_MESSAGE =
        "Wallet request cancelled. Nothing was signed."
}
