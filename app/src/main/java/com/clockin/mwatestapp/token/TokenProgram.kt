package com.clockin.mwatestapp.token

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

/**
 * Hand-built SPL Token "Transfer" instruction (classic Token program, instruction
 * index 3: [u8 opcode=3][u64 amount, little-endian]). This byte layout is part of
 * the stable SPL Token program spec, not something the client library versions.
 */
object TokenProgram {
    val PROGRAM_ID: SolanaPublicKey =
        SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")

    fun transfer(
        source: SolanaPublicKey,
        destination: SolanaPublicKey,
        owner: SolanaPublicKey,
        amount: Long
    ): TransactionInstruction {
        val data = ByteArray(9)
        data[0] = 3
        for (i in 0 until 8) {
            data[1 + i] = ((amount ushr (8 * i)) and 0xFFL).toByte()
        }
        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(source, false, true),
                AccountMeta(destination, false, true),
                // owner is also the fee payer, so it must be writable, not just a signer.
                AccountMeta(owner, true, true)
            ),
            data
        )
    }
}
