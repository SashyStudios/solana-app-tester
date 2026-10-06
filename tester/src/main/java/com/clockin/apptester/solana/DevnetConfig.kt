package com.clockin.apptester.solana

import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.Solana

/**
 * Devnet is the only cluster this tool is allowed to touch. requireDevnet() is the
 * enforcement point, not a comment: every wallet request calls it before doing anything,
 * so switching CLUSTER to mainnet by accident fails loudly at the first request instead of
 * silently sending a real transaction.
 */
object DevnetConfig {

    /** Unused in Stage 1 (connect only, no RPC calls) - the signing stage reads it. */
    const val RPC_URL = "https://api.devnet.solana.com"

    val CLUSTER: Blockchain = Solana.Devnet

    /** Shown in the UI badge so the cluster is never a guess for whoever is watching. */
    const val CLUSTER_LABEL = "DEVNET"

    /**
     * The devnet test wallet this tool expects to be connected. When the connected address
     * differs, the UI shows a warning - it does not block, because only the person holding
     * the phone can tell a wrong-wallet mistake from a deliberate second test wallet.
     * An empty string disables the check entirely.
     */
    const val EXPECTED_TEST_WALLET = "4BAHsk1dFpKmp1kuZyK8ziSsTgpqgjtGqXwK3Ppb8wF2"

    /**
     * Throws if anything other than solana:devnet is about to be used. Compares fullName
     * ("solana:devnet") rather than object identity so a Blockchain built some other way
     * can't slip past.
     */
    fun requireDevnet(blockchain: Blockchain) {
        if (blockchain.fullName != Solana.Devnet.fullName) {
            throw IllegalStateException(
                "Refusing to use cluster '${blockchain.fullName}'. " +
                    "Solana App Tester is ${Solana.Devnet.fullName} only."
            )
        }
    }

    /** False only when a specific test wallet is configured and the connected one isn't it. */
    fun isExpectedTestWallet(address: String): Boolean =
        EXPECTED_TEST_WALLET.isEmpty() || address == EXPECTED_TEST_WALLET
}
