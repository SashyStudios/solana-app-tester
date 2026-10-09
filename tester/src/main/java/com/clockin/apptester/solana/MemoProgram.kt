package com.clockin.apptester.solana

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

/**
 * SPL Memo program instruction. The memo program's instruction data is simply the raw
 * UTF-8 bytes of the memo itself - there is no opcode and no length prefix, unlike the
 * hand-built SPL Token layout in the demo app.
 *
 * The signer list is what the memo program verifies: each account passed must have signed.
 * Only the connected wallet is passed, marked signer AND writable because the same account
 * is also the fee payer for this transaction (the fee payer's lamport balance changes, so
 * it has to be writable).
 */
object MemoProgram {
    val PROGRAM_ID: SolanaPublicKey =
        SolanaPublicKey.from("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")

    fun memo(memo: String, signer: SolanaPublicKey): TransactionInstruction =
        TransactionInstruction(
            PROGRAM_ID,
            listOf(AccountMeta(signer, true, true)),
            memo.toByteArray(Charsets.UTF_8)
        )
}
