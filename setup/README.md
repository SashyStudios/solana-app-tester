# SKR devnet token setup

One-time setup that creates the devnet SPL token ("SKR") the MWA Test Target App
transfers in its "Send Test Transaction" / "Send Large Transaction" buttons.

This is infrastructure setup, not app code - it only needs to run once per devnet
test wallet, from a machine that has the Solana CLI installed.

## Prerequisites
- Solana CLI tools (`solana`, `spl-token`, `solana-keygen`) on PATH.
  Install: https://solana.com/docs/intro/installation
- A devnet-mode Phantom or Solflare wallet installed on the test device, with its
  public key handy.

## Steps
1. Open `create_skr_devnet_token.ps1` and set `$TestWalletAddress` to your devnet
   wallet's public key.
2. Run the script in PowerShell. It pauses twice to ask you to paste an address
   back in - copy it from the `spl-token` output printed just above the prompt.
3. Copy the three addresses printed at the end into
   `app/src/main/java/com/clockin/mwatestapp/SolanaConfig.kt`
   (`SKR_MINT_B58`, `PRIMARY_TOKEN_ACCOUNT_B58`, `SECONDARY_TOKEN_ACCOUNT_B58`).

## Why two token accounts for one wallet
The app transfers SKR from the wallet's primary token account to a second token
account also owned by the same wallet, rather than transferring an account into
itself. Both design the same way from the user's perspective (funds never leave
the test wallet) - splitting across two accounts just avoids relying on the SPL
Token program's handling of a transfer instruction that names the same account as
both source and destination, which isn't something worth risking on the one
transaction the whole demo hinges on.
