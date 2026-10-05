# One-time devnet setup for the SKR test token used by the MWA Test Target App.
#
# Prerequisite: Solana CLI tools installed and on PATH (`solana`, `spl-token`,
# `solana-keygen`). See https://solana.com/docs/intro/installation
#
# This script was NOT run or tested by Claude - the sandbox it was written in has
# no Solana CLI installed. Run it yourself, interactively, and read the addresses
# it prints rather than assuming any output-parsing - CLI output format can change.
#
# Usage:
#   1. Edit $TestWalletAddress below to your devnet Phantom/Solflare public key.
#   2. Run this script from PowerShell. It will pause twice asking you to paste
#      an address back in - copy it from the command output directly above the prompt.
#   3. Take the three addresses printed at the end and paste them into
#      app/src/main/java/com/clockin/mwatestapp/SolanaConfig.kt

$TestWalletAddress = "4BAHsk1dFpKmp1kuZyK8ziSsTgpqgjtGqXwK3Ppb8wF2"

if ($TestWalletAddress -eq "REPLACE_WITH_YOUR_DEVNET_TEST_WALLET_ADDRESS") {
    Write-Error "Edit `$TestWalletAddress` in this script first (your devnet test wallet's public key)."
    exit 1
}

solana config set --url https://api.devnet.solana.com

# Throwaway mint-authority / fee-payer keypair for setup only - this is NOT the test wallet.
$AuthorityKeyPath = Join-Path $PSScriptRoot "skr-mint-authority.json"
if (-not (Test-Path $AuthorityKeyPath)) {
    solana-keygen new --no-bip39-passphrase --outfile $AuthorityKeyPath
}
solana airdrop 2 --keypair $AuthorityKeyPath

Write-Output ""
Write-Output "=== Creating SKR mint (6 decimals) ==="
spl-token create-token --decimals 6 --fee-payer $AuthorityKeyPath --mint-authority $AuthorityKeyPath
$MintAddress = Read-Host "`nPaste the token address printed above (this is SKR_MINT)"

Write-Output ""
Write-Output "=== Creating primary token account (test wallet's spendable SKR) ==="
spl-token create-account $MintAddress --owner $TestWalletAddress --fee-payer $AuthorityKeyPath
$PrimaryTokenAccount = Read-Host "`nPaste the account address printed above (this is PRIMARY_TOKEN_ACCOUNT)"

# Secondary token account, also owned by the test wallet - the self-transfer destination,
# so the app never has to transfer an account into itself.
$SecondaryKeyPath = Join-Path $PSScriptRoot "skr-secondary-account.json"
if (-not (Test-Path $SecondaryKeyPath)) {
    solana-keygen new --no-bip39-passphrase --outfile $SecondaryKeyPath
}
Write-Output ""
Write-Output "=== Creating secondary token account (self-transfer destination) ==="
spl-token create-account $MintAddress --owner $TestWalletAddress --fee-payer $AuthorityKeyPath $SecondaryKeyPath
$SecondaryTokenAccount = Read-Host "`nPaste the account address printed above (this is SECONDARY_TOKEN_ACCOUNT)"

Write-Output ""
Write-Output "=== Minting 1,000 test SKR into the primary account ==="
spl-token mint $MintAddress 1000 $PrimaryTokenAccount --mint-authority $AuthorityKeyPath --fee-payer $AuthorityKeyPath

Write-Output ""
Write-Output "=== Done - paste these into SolanaConfig.kt ==="
Write-Output "SKR_MINT_B58                 = $MintAddress"
Write-Output "PRIMARY_TOKEN_ACCOUNT_B58    = $PrimaryTokenAccount"
Write-Output "SECONDARY_TOKEN_ACCOUNT_B58  = $SecondaryTokenAccount"
