package com.clockin.mwatestapp

import com.solana.publickey.SolanaPublicKey

/**
 * Devnet SKR token addresses. These are created once, out of band, by
 * setup/create_skr_devnet_token.ps1 - the app never derives or mints them itself.
 * Fill in the three REPLACE_ values below after running that script.
 */
object SolanaConfig {
    const val DEVNET_RPC_URL = "https://api.devnet.solana.com"

    private const val SKR_MINT_B58 = "REPLACE_WITH_SKR_MINT_ADDRESS"
    private const val PRIMARY_TOKEN_ACCOUNT_B58 = "REPLACE_WITH_PRIMARY_TOKEN_ACCOUNT_ADDRESS"
    private const val SECONDARY_TOKEN_ACCOUNT_B58 = "REPLACE_WITH_SECONDARY_TOKEN_ACCOUNT_ADDRESS"

    val SKR_MINT: SolanaPublicKey get() = SolanaPublicKey.from(SKR_MINT_B58)

    /** The connected wallet's spendable SKR balance. Source of both transfer buttons. */
    val PRIMARY_TOKEN_ACCOUNT: SolanaPublicKey get() = SolanaPublicKey.from(PRIMARY_TOKEN_ACCOUNT_B58)

    /** A second token account owned by the same test wallet - the self-transfer destination. */
    val SECONDARY_TOKEN_ACCOUNT: SolanaPublicKey get() = SolanaPublicKey.from(SECONDARY_TOKEN_ACCOUNT_B58)

    private const val SKR_DECIMALS_FACTOR = 1_000_000L // 6 decimals, matches create-token --decimals 6

    /** "Send Test Transaction": 10 SKR - well within the funded balance. */
    const val TEST_TRANSFER_AMOUNT: Long = 10L * SKR_DECIMALS_FACTOR

    /** "Send Large Transaction": 1,000,000 SKR - guaranteed to exceed any realistic funded balance. */
    const val LARGE_TRANSFER_AMOUNT: Long = 1_000_000L * SKR_DECIMALS_FACTOR
}
