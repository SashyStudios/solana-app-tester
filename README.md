# Solana App Tester

Catch what your update broke.

Record your app once. Check it again with one tap.

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
- WebView content is untested. A web page inside an app reaches accessibility as
  virtual nodes built from the page, not as Android views, so resource IDs may be
  missing there and matching or clicking inside a WebView is unverified.
- Web apps opened in Chrome are recorded as Chrome, and replay can't reopen the
  page. Roadmap: record the page URL and relaunch it.
- Controls that don't use standard Android click handling (Jetpack Compose
  screens, and possibly custom-drawn views and games) don't send a click event,
  so they can't be recorded automatically. Some controls don't announce taps to
  accessibility services, so they can't be recorded. In testing, the Order Online
  button of a food-ordering app was one. The tool counts taps it saw but couldn't
  use, and it can't see taps the app never announces. Replay always starts from the app's launch screen and doesn't scroll, so
  apps whose screens depend on state (opening hours, login, scroll position) can
  show a different screen on replay.
- A label that looks sensitive (more than 4 digits in a row, an email address, or
  a hex or base58-looking string over 20 characters) is removed from the step and
  the step is marked. A step whose only label was removed can't be matched by that
  label on replay.
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

## Privacy and safety

- **Recordings stay on your device.** Flows are saved as JSON in the app's own
  private internal storage. There is no cloud sync, no account, and no database.
- **Nothing is sent anywhere.** The tester makes no network calls. Wallet connect
  talks to the wallet app over a local socket on the device, and sends only this
  app's name and the cluster — never your recorded steps, screen labels or reports.
- **What a recording contains.** For each step you tap: the app's package name, the
  view's class, its resource ID, and its visible label (text or contentDescription).
  No screenshots, no coordinates, no typed text.
- **Devnet only.** The cluster is checked in code before every wallet request and
  anything other than Solana devnet is refused. The tester never taps or approves
  anything inside a wallet app — you approve every request by hand.
- **Wallets and system screens are skipped.** Known wallet apps and system
  credential screens are on a protected list: the tester drops their events without
  reading or recording anything on screen, and replay skips any step whose screen
  belongs to one rather than tapping it. Recording will not start, and a recording in
  progress is stopped with a message, if a protected app (including Phantom,
  Solflare, Jupiter and the Solana Mobile wallet) is in the foreground or the
  lock screen is showing. Only the app's package name is ever written to the log in
  those cases. The protected-app list covers known wallets and system screens only.
  An unlisted app is still fully visible.
- **Password fields.** Text and contentDescription are never read from a node Android
  marks as a password field.
- **Limits you should know about.** The accessibility service sees every app, not
  just the one you are testing, so a recording that passes through another app can
  capture that app's on-screen labels too. Real password fields are skipped. A PIN
  pad built from ordinary buttons in an unlisted app can still be recorded, except
  that labels that look like digit runs are removed. Use a throwaway devnet wallet
  rather than a real one.
- **Taps seen but not recorded.** If a tap reaches the tester but carries too little
  to become a step, it is counted and the count is shown ("N taps seen but not
  recorded"). Taps an app never announces to accessibility can't be counted.
- **Saving and deleting.** Recordings save automatically when you stop, and a flow
  can be deleted from the list. There is no review step yet.
- **First-run notice.** The first time you record, a one-time dialog explains that
  the tool can read other apps' screens while recording. Recording starts only
  after you dismiss it with "Got it".

## Roadmap

- Modular flows — record each screen as its own segment, then chain segments
  into a full replay.
- A state graph for flows, instead of a flat step list.
- Recording Jetpack Compose apps.
- Save and Discard review step after recording.
- User-editable protected-app list.
- Guided capture, where you pick a control from a list of what's on screen.
- Wallet-state detection: connect dialog, approval screen, rejection,
  insufficient funds.
- Real devnet SKR test transactions wired into replay.

## Safety

Devnet only. No private keys or mint authority files are in this repo.
