# MWA Test Target App

This is a minimal throwaway native Android app built as a test target for the
Solana Mobile App Tester (built for the CLOCK IN Solana Mobile hackathon).

**This is not a real product.** It exists only to give the tester something real
to record and replay against — a small app with a genuine Mobile Wallet Adapter
(MWA) integration on devnet.

## What it does
- **Connect Wallet** — triggers a standard MWA wallet-connect flow.
- **Send Test Transaction** — requests a small, reasonable amount of devnet SKR,
  triggering the normal MWA approval screen.
- **Send Large Transaction** — intentionally requests more devnet SKR than the
  connected wallet holds, reliably triggering the insufficient-funds error state
  on demand.

## Why it exists
The tester needs real Mobile Wallet Adapter screens to detect and pattern-match
against (wallet connect, approval, rejection, insufficient funds). Testing
against a real published app or a web app (like our other project, FMN) wasn't
viable — see the main project's CLAUDE.md for the full reasoning. This app is
small and fully under our control so it's safe to deliberately break something
between recorded versions for the demo.

## Planned demo bug
See `CLAUDE.md` in the tester's own repo for the locked demo scenario
(crash-on-rejection between version 1 and version 2 of this app).

## Status
Built specifically for hackathon submission (deadline Oct 8, 2026). No plans to
maintain or publish beyond that.
