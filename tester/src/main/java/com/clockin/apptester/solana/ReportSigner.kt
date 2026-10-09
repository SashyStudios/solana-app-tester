package com.clockin.apptester.solana

import com.clockin.apptester.model.RecordedStep
import com.clockin.apptester.model.ReplayResult
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.networking.KtorNetworkDriver
import com.solana.publickey.SolanaPublicKey
import com.solana.rpc.SolanaRpcClient
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.base.Base58
import java.io.IOException

/** A signature that actually came back from the wallet, with everything needed to check it
 *  independently. Only ever constructed from a real signature - see signResult(). */
data class SignedProof(
    val canonical: String,
    val hash: String,
    val signature: String,
    val explorerUrl: String
)

/**
 * Stage 2: publishes a SHA-256 hash of the last replay result to devnet as an SPL Memo.
 *
 * Devnet is re-checked through DevnetConfig.requireDevnet before the wallet is contacted.
 * The person approves every signature by hand in their wallet - nothing here taps,
 * approves or dismisses anything in a wallet app, and nothing retries automatically: a
 * cancelled or failed request ends and waits for a deliberate second tap.
 */
object ReportSigner {

    /** Outer backstop around the whole wallet round-trip, which includes the time the
     *  person spends reading and approving the request in their wallet. */
    private const val SIGN_TIMEOUT_MS = 60_000L

    private val rpc by lazy { SolanaRpcClient(DevnetConfig.RPC_URL, KtorNetworkDriver()) }

    private val _inProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    private val _message = MutableStateFlow<WalletMessage?>(null)
    val message: StateFlow<WalletMessage?> = _message.asStateFlow()

    /** The last proof that was actually published. Never set from a cancelled, failed or
     *  timed-out request, so the UI can only ever show a signature that exists. */
    private val _lastProof = MutableStateFlow<SignedProof?>(null)
    val lastProof: StateFlow<SignedProof?> = _lastProof.asStateFlow()

    fun explorerUrl(signature: String): String =
        "https://explorer.solana.com/tx/$signature?cluster=devnet"

    /**
     * Hashes the given replay result and asks the connected wallet to sign and send a memo
     * transaction carrying that hash. Reuses the existing authorization held by
     * WalletConnector's adapter - it does not start a second, separate session.
     */
    suspend fun signResult(
        sender: ActivityResultSender,
        flowName: String,
        targetPackage: String,
        steps: List<RecordedStep>,
        result: ReplayResult
    ) {
        if (_inProgress.value) return
        val adapter = WalletConnector.adapter
        DevnetConfig.requireDevnet(adapter.blockchain)

        val canonical = ReportHash.canonicalString(flowName, targetPackage, steps, result)
        val hash = ReportHash.sha256Hex(canonical)

        _inProgress.value = true
        _message.value = null
        try {
            val outcome = withTimeoutOrNull(SIGN_TIMEOUT_MS) {
                adapter.transact(sender) { authResult ->
                    withContext(Dispatchers.IO) {
                        val signer = SolanaPublicKey(authResult.accounts.first().publicKey)

                        val blockhashResponse = rpc.getLatestBlockhash()
                        val blockhash = blockhashResponse.result?.blockhash
                            ?: throw BlockhashException(
                                blockhashResponse.error?.message ?: "no blockhash in the RPC response"
                            )

                        val transaction = Transaction(
                            Message.Builder()
                                .setRecentBlockhash(blockhash)
                                .addInstruction(MemoProgram.memo(hash, signer))
                                .build()
                        )

                        try {
                            val sent = signAndSendTransactions(transaction)
                            val signature = sent.signatures.firstOrNull()
                                ?: throw IllegalStateException("The wallet returned no signature.")
                            Base58.encode(signature)
                        } catch (methodNotFound: JsonRpc20Client.JsonRpc20RemoteException) {
                            // Some wallets implement only sign_transactions. -32601 is
                            // JSON-RPC "method not found": sign it there, submit it here.
                            if (methodNotFound.code != JSON_RPC_METHOD_NOT_FOUND) throw methodNotFound
                            val signed = signTransactions(transaction).firstOrNull()
                                ?: throw IllegalStateException("The wallet returned no signed transaction.")
                            val sendResponse = rpc.sendTransaction(signed)
                            sendResponse.result
                                ?: throw SubmitException(
                                    sendResponse.error?.message ?: "the devnet RPC did not return a signature"
                                )
                        }
                    }
                }
            }

            when (outcome) {
                null -> _message.value = WalletMessage(WalletConnector.TIMEOUT_MESSAGE, isError = true)
                is TransactionResult.Success -> {
                    val signature = outcome.payload
                    _lastProof.value = SignedProof(
                        canonical = canonical,
                        hash = hash,
                        signature = signature,
                        explorerUrl = explorerUrl(signature)
                    )
                    _message.value = WalletMessage("Result signed on devnet.", isError = false)
                }
                is TransactionResult.NoWalletFound ->
                    _message.value = WalletMessage(NO_WALLET_MESSAGE, isError = true)
                is TransactionResult.Failure ->
                    _message.value = WalletMessage(failureMessage(outcome.message, outcome.e), isError = true)
            }
        } finally {
            _inProgress.value = false
        }
    }

    /** Thrown when devnet wouldn't give us a blockhash - kept distinct from a submit
     *  failure so the message can say which half of the round trip failed. */
    private class BlockhashException(message: String) : Exception(message)
    private class SubmitException(message: String) : Exception(message)

    /**
     * Plain language for each outcome, and never a guess: a cancel is only called a cancel
     * when the wallet's own message or error code says so (RESULT_CANCELED arrives as
     * "Request was interrupted", a refusal as the protocol's ERROR_NOT_SIGNED). Anything
     * unrecognized is passed through verbatim rather than relabelled.
     */
    private fun failureMessage(raw: String, cause: Exception?): String {
        val remote = findRemoteException(cause)
        when (remote?.code) {
            ERROR_NOT_SIGNED -> return CANCELLED_MESSAGE
            ERROR_CLUSTER_NOT_SUPPORTED -> return CLUSTER_NOT_SUPPORTED_MESSAGE
        }
        findCause<BlockhashException>(cause)?.let {
            return "Couldn't get a recent blockhash from devnet: ${it.message}. Nothing was signed."
        }
        findCause<SubmitException>(cause)?.let {
            return "The wallet signed it, but devnet didn't accept it: ${it.message}."
        }
        findCause<IOException>(cause)?.let {
            return "Can't reach devnet. Check this device's connection. Nothing was signed."
        }
        val lower = raw.lowercase()
        if (lower.contains("interrupted") || lower.contains("cancel") || lower.contains("did not authorize")) {
            return CANCELLED_MESSAGE
        }
        return "Signing failed: $raw"
    }

    private fun findRemoteException(cause: Throwable?): JsonRpc20Client.JsonRpc20RemoteException? =
        findCause<JsonRpc20Client.JsonRpc20RemoteException>(cause)

    /** Walks the cause chain - the adapter wraps whatever the block threw. */
    private inline fun <reified T : Throwable> findCause(cause: Throwable?): T? {
        var current = cause
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private const val JSON_RPC_METHOD_NOT_FOUND = -32601

    /** From the MWA protocol contract. This clientlib (2.2.0) names the cluster/chain
     *  mismatch ERROR_CLUSTER_NOT_SUPPORTED (-7); there is no ERROR_CHAIN_NOT_SUPPORTED
     *  constant in it. Same condition, different name - see CLAUDE.md status. */
    private const val ERROR_CLUSTER_NOT_SUPPORTED = -7
    private const val ERROR_NOT_SIGNED = -3

    private const val CANCELLED_MESSAGE = "You cancelled. Nothing was sent."
    private const val CLUSTER_NOT_SUPPORTED_MESSAGE =
        "This wallet doesn't support Solana devnet. Switch it to devnet, or use a wallet that does. " +
            "Nothing was signed."
    private const val NO_WALLET_MESSAGE = "No compatible wallet app found on this device."
}
