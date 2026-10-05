package com.clockin.mwatestapp

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clockin.mwatestapp.token.TokenProgram
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.networking.KtorNetworkDriver
import com.solana.publickey.SolanaPublicKey
import com.solana.rpc.SolanaRpcClient
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.base.Base58

enum class FlowStatus { IDLE, CONNECTING, AWAITING_APPROVAL, SUCCESS, CANCELLED, FAILED, NO_WALLET_FOUND }

data class MainUiState(
    val status: FlowStatus = FlowStatus.IDLE,
    val walletAddress: String? = null,
    val lastSignature: String? = null,
    val message: String = ""
)

class MainViewModel : ViewModel() {

    private val walletAdapter = MobileWalletAdapter(
        ConnectionIdentity(
            identityUri = Uri.parse("https://clockin.hackathon"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "MWA Test Target App"
        )
    ).apply {
        // Devnet only - see CLAUDE.md. This is already the library default; set explicitly anyway.
        blockchain = Solana.Devnet
    }

    private val rpc = SolanaRpcClient(SolanaConfig.DEVNET_RPC_URL, KtorNetworkDriver())

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    fun connectWallet(sender: ActivityResultSender) {
        viewModelScope.launch {
            _uiState.update { it.copy(status = FlowStatus.CONNECTING, message = "Connecting wallet...") }
            val result = walletAdapter.connect(sender)
            when (result) {
                is TransactionResult.Success -> {
                    val address = SolanaPublicKey(result.authResult.accounts.first().publicKey).base58()
                    _uiState.update {
                        it.copy(status = FlowStatus.SUCCESS, walletAddress = address, message = "Wallet connected")
                    }
                }
                is TransactionResult.NoWalletFound -> {
                    _uiState.update { it.copy(status = FlowStatus.NO_WALLET_FOUND, message = result.message) }
                }
                is TransactionResult.Failure -> {
                    _uiState.update { it.copy(status = FlowStatus.FAILED, message = result.message) }
                }
            }
        }
    }

    fun sendTestTransaction(sender: ActivityResultSender) {
        sendTransfer(sender, SolanaConfig.TEST_TRANSFER_AMOUNT)
    }

    fun sendLargeTransaction(sender: ActivityResultSender) {
        sendTransfer(sender, SolanaConfig.LARGE_TRANSFER_AMOUNT)
    }

    private fun sendTransfer(sender: ActivityResultSender, amount: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(status = FlowStatus.AWAITING_APPROVAL, message = "Requesting approval...") }

            val result = walletAdapter.transact(sender) { authResult ->
                withContext(Dispatchers.IO) {
                    val owner = SolanaPublicKey(authResult.accounts.first().publicKey)

                    val blockhashResponse = rpc.getLatestBlockhash()
                    val blockhash = blockhashResponse.result?.blockhash
                        ?: throw IllegalStateException(
                            blockhashResponse.error?.message ?: "Failed to fetch latest blockhash"
                        )

                    val instruction = TokenProgram.transfer(
                        source = SolanaConfig.PRIMARY_TOKEN_ACCOUNT,
                        destination = SolanaConfig.SECONDARY_TOKEN_ACCOUNT,
                        owner = owner,
                        amount = amount
                    )

                    val message = Message.Builder()
                        .setRecentBlockhash(blockhash)
                        .addInstruction(instruction)
                        .build()

                    val sendResult = signAndSendTransactions(Transaction(message))
                    val signature = sendResult.signatures.firstOrNull()
                        ?: throw IllegalStateException("Wallet returned no signature")

                    Base58.encode(signature)
                }
            }

            when (result) {
                is TransactionResult.Success -> {
                    _uiState.update {
                        it.copy(
                            status = FlowStatus.SUCCESS,
                            lastSignature = result.payload,
                            message = "Transaction sent: ${result.payload}"
                        )
                    }
                }
                is TransactionResult.NoWalletFound -> {
                    _uiState.update { it.copy(status = FlowStatus.NO_WALLET_FOUND, message = result.message) }
                }
                is TransactionResult.Failure -> {
                    // Baseline (v1) rejection handling - CLAUDE.md's planned demo bug breaks
                    // exactly this branch in v2, so it stays a distinct, identifiable path
                    // rather than falling through to the generic failure case below.
                    if (result.message.contains("did not authorize", ignoreCase = true)) {
                        _uiState.update {
                            it.copy(status = FlowStatus.CANCELLED, message = "Transaction cancelled")
                        }
                    } else {
                        _uiState.update { it.copy(status = FlowStatus.FAILED, message = result.message) }
                    }
                }
            }
        }
    }
}
