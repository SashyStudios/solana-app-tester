# Solana App Tester

Record once. Replay every change.

Record a flow in your Android app once, replay it after every change, and get a
plain explanation of what broke.

Built for the CLOCK IN Solana Mobile hackathon.

## Why I built it

I build apps alone. For a long time I couldn't get anyone to test them. Family
and friends wouldn't, even when I gave the app away and sat down to go through
it with them. The only thing that worked was paying people a few dollars to
use my memorial app, and that's how I found most of my bugs. But after any big
change, something somewhere might have broken, and checking meant tapping
through every step again myself.

So I built Solana App Tester. Record a flow once, replay it after every
change, and see what broke. It doesn't replace real testers or real feedback.
It replaces the part where I re-tap the same flow by hand.

## What it does today

- Records one-finger taps on classic Android View-based apps — no touch
  exploration, no interception. Tap your app normally while recording.
- Replays by element identity (resource ID, then pre-tap label, then post-tap
  text as a last resort), not screen coordinates.
- Stops at the first failed step and reports what it expected, what's actually
  on screen, and a closest-match guess if one exists.
- Saves one flow per take, grouped by app, renamable and deletable — nothing
  is overwritten or auto-deleted.
- Shows a small status pill while recording or replaying: step count, last
  step captured, auto-stop countdown, pass/fail state.
- Copy report: a plain-text summary (target app, device info, recorded steps,
  full replay log) ready to paste into a bug report.
- A throwaway demo app (two build flavors, v1 and v2) that plants a real
  break — a button's resource ID and label both change between versions — so
  the tester has something concrete to catch.

## Limits

- Automatic recording works for classic View-based apps. It does not yet
  record Jetpack Compose apps — Compose doesn't emit the same accessibility
  click event a plain tap produces, so nothing gets captured.
- An element with no resource ID and no text/label can't be matched
  reliably. The tester warns about these when they show up in a recording.
- Replay performs the real actions it recorded — real taps, on the real app.
  It is not a simulation.
- Wallet-state detection (connect dialog, approval screen, rejection,
  insufficient funds) is not built yet. That's next.
- When a step fails, the tester reports an observable difference — the
  element it expected is gone, renamed, or moved. It does not know what code
  change caused that, and doesn't claim to.

## How to run it

1. Install both APKs:
   - `Solana App Tester` (module `tester`) — the recording/replay tool.
   - The demo app (module `app`) — what you'll record and replay against.
2. In Android Settings, enable the tester's accessibility service by hand
   (it asks for confirmation — this isn't done for you automatically).
3. Open the tester, tap **Record**, then use the demo app normally.
4. Tap **Stop** (or let it auto-stop). The flow saves automatically.
5. Tap **Replay** to run it again and see pass/fail with a plain-text log.

### Demo: catching a real break

The demo app ships as two flavors sharing one `applicationId`, so v2 installs
right over v1:

- `scripts\install-demo-v1.bat`
- `scripts\install-demo-v2.bat`

Each script resolves `adb` itself and installs the matching debug APK,
telling you which Gradle task to run first (`gradlew :app:assembleV1Debug` /
`:app:assembleV2Debug`) if it isn't built yet. Neither script touches the
tester or uninstalls anything.

To see the tester catch a break:

1. Install `v1` with `scripts\install-demo-v1.bat`.
2. Record a flow in the tester that taps **Check status** in the demo app's
   "Safe test actions" section.
3. Replay it — it should pass.
4. Install `v2` with `scripts\install-demo-v2.bat` (installs over v1, same
   app, same data).
5. Replay the same recorded flow — it should fail. In v2, **Check status**
   (`btnCheck`) was renamed to **Run check** (`btnStatusCheck`): the resource
   ID and label both changed. The tester's report should name
   `btnStatusCheck` / "Run check" as the likely rename.

## Roadmap

- Modular flows — record each screen as its own segment, then chain segments
  into a full replay.
- A state graph for flows, instead of a flat step list.
- Recording Jetpack Compose apps.
- Wallet-state detection: connect dialog, approval screen, rejection,
  insufficient funds.
- Real devnet SKR test transactions wired into replay.

## Safety

Devnet only. No private keys or mint authority files are in this repo.
