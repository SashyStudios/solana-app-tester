# Project: Solana Mobile App Tester (CLOCK IN Hackathon)

## What this is
A native Android tool that lets Solana Mobile developers record a test flow through
their own app once (wallet connect, transaction approval, etc.) and replay it on
future builds to catch regressions. Built for the CLOCK IN Solana Mobile hackathon.
Submission deadline: Oct 8, 2026.

## Who uses this
The users are developers building Solana Mobile apps. Every decision should serve them:
- Truthful output: logs, step names and results must be accurate and never misleading, even cosmetically. A wrong label in a test tool is a product defect.
- Explain failures: say what was expected, what was found, and show the screenshot. Report observable differences only, never claim to know the code change.
- Be honest about limits: if the tool can't see something (for example a Compose screen), say so in the output instead of failing silently.
- Safe by default: devnet only; warn or refuse if a real/mainnet wallet is involved.
- Repeatable: the same flow gives the same result every run.
- Fast to start: setup state and next step must be obvious on the main screen.
- Shareable: results copyable as plain text (stretch, after core features).
When a design choice is unclear, choose what a developer would want to read in a bug report.

## Working relationship
- Full-file drops only — never partial patches or diffs.
- Push back on scope creep. If a suggested feature isn't in the MUST-HAVE list below,
  flag it and ask before building it.
- Debugger first (read-only, no changes), then a separate fix prompt. Never combine
  debugging and fixing in one pass.
- Commits go directly to main.
- Ask for clarification rather than guessing on anything touching the wallet/transaction
  flow — that code path is the one piece that has to actually work on demo day.

## Tech stack
- Kotlin, Android Studio, native Android (no web wrapper — hackathon rules require this)
- Android Accessibility Service for recording/replaying UI flows
- Solana Mobile Wallet Adapter (`mobile-wallet-adapter-clientlib-ktx`) for wallet
  connect/approve flows
- Devnet only for all transactions during development — SKR as test currency
- Test wallet: Phantom or Solflare in devnet mode, installed alongside the tool on
  the test device

## Scope — LOCKED, do not expand without asking

### Must-have (this is the demo)
1. **Recording** — user manually walks through one flow in their own app (wallet
   connect → approve/reject a transaction). Accessibility Service captures the
   sequence of screens/actions.
2. **Replay** — button triggers the tool to re-run that exact recorded sequence on
   the same app.
3. **MWA-specific detection** — recognize exactly these four states (see spec below):
   wallet connect dialog, transaction approval screen, transaction rejected,
   insufficient funds error. Hard-coded pattern recognition, not generalized UI
   understanding.
4. **Break detection** — on replay, if a recognized screen doesn't appear where
   expected, or an unrecognized screen appears instead, flag "flow changed" with a
   screenshot.
5. **One real SKR devnet transaction** — replay actually triggers a genuine devnet
   transaction using SKR, not a UI simulation. This is the anchor for the SKR bonus
   category — it has to be real.
6. **Results screen** — pass/fail per recorded flow, screenshot of what broke.
7. **Throwaway demo target app** — a minimal separate native app (one screen,
   "Connect Wallet" button, "Send Test Transaction" button wired to MWA, "Send
   Large Transaction" button that intentionally requests more SKR than the test
   wallet holds). This is what the tester records/replays against for the demo.
   Not FMN, not a published app — a small app we fully control so we can
   deliberately break something between versions and film the tool catching it.

## Planned demo bug (locked)
Version 1: user taps "reject" on the wallet approval screen → app shows a clean
"transaction cancelled" message. This is the recorded baseline flow.
Version 2 (the planted regression): the rejection-handling callback is broken —
tapping "reject" now crashes the app instead of showing the cancellation message.
Replay of the recorded flow against version 2 should flag this as a break.

Why this bug, not a units/insufficient-funds mixup: it's visually unambiguous in a
demo video (a crash reads instantly to any viewer, technical or not), it's a
realistic and common real-world bug class (rejection paths are the least-tested
path in most apps), and it still requires MWA-specific understanding to catch
correctly — the tool has to know "reject was tapped, therefore a graceful
cancellation screen is the expected next state" to correctly flag the crash as a
regression in *that* flow, not just "app crashed, unclear why."

Note for build: detecting "target app crashed/force-closed" is a different code
path from detecting "wrong screen appeared" — flag this explicitly when building
break-detection logic, don't assume the same matching logic covers both.

The insufficient-funds button ("Send Large Transaction") still gets built and still
gets exercised as one of the four MWA states in the must-have list — it's just not
the bug featured in the demo video itself.

### Explicitly cut — do not build unless everything above is done early
- Multiple saved flows / flow library (v1 = one active recording at a time)
- Generalized "works on any app" support — one demo app only, made rock-solid
- Cloud sync, team sharing, dashboards
- Polished onboarding — rough UI is fine if the tool works
- Error-state detection beyond the four states listed above

### Fallback if MWA recognition isn't working by day 6
Cut to: generic replay + break detection only, with the SKR piece downgraded to a
simple stake/payment gate instead of full transaction testing. Still real Solana
interaction, just less ambitious. Flag this decision explicitly if it happens —
don't silently descope.

## The four MWA states to detect
1. **Wallet connect dialog** — the screen/intent triggered when the target app
   requests a wallet connection via MWA. Needs: recognize it appeared, be able to
   tap "approve"/"connect" to proceed.
2. **Transaction approval screen** — shown when the target app requests a signature.
   Needs: recognize it appeared, tap "approve" to proceed (this is what triggers the
   real devnet SKR transaction).
3. **Transaction rejected** — the state after a user (or the tool) declines approval.
   Needs: recognize this as an expected end-state, not a "break."
4. **Insufficient funds error** — a specific failure state distinct from a generic
   crash. Needs: recognize and label it correctly rather than flagging it as
   "unrecognized screen."

Use UI Automator Viewer to inspect the actual view hierarchy of each state before
writing pattern-matching logic — don't guess at resource IDs or text content.

## Build order (11 days from Sep 27, submission due Oct 8)
- **Day 1**: Build the throwaway demo app first (Connect Wallet + Send Test
  Transaction, wired to MWA). Nothing downstream has a real target without this.
  Then start Accessibility Service raw recording/replay against it, no MWA
  awareness yet.
- **Days 2–3**: Continue Accessibility Service raw recording + replay.
- **Days 4–6**: Layer in MWA-specific screen recognition (the four states above).
  Checkpoint: if this isn't working by end of day 6, trigger the fallback plan.
- **Days 7–8**: Wire a real SKR devnet transaction into replay.
- **Day 9**: Break-detection logic + results screen.
- **Day 10**: Demo video (max 3 min), clean up GitHub repo so it clones and builds
  from scratch, pitch deck.
- **Day 11**: Buffer. Don't schedule real feature work here — something will break
  and this is the day to fix it, not build something new.

## Deliverables checklist (hackathon requirements)
- [ ] Working Android APK
- [ ] Demo video, 3 minutes max, showing the tool actually catching a real bug
- [ ] GitHub repo that clones and runs — verify this yourself before submitting,
      don't assume
- [ ] Pitch deck / short presentation
- [ ] Origin story: Josh's real testing pain point (hard to find testers, building and
      testing solo). Use it in the README intro, the first 20-30 seconds of the demo
      video, and slide 2 of the pitch deck. Needs Josh's own words before it can be
      drafted.

## Branding — Sashy Studios identity (apply lightly, not deep per-screen polish)
This app is a Sashy Studios product and should read as one at a glance — in the demo
video and any pitch deck screenshots especially, since that's the screen time that
actually matters here. Don't spend deep design time on every screen; apply these
consistently across the handful of screens that exist (tester main screen, results
screen, the three demo-app buttons) rather than custom-designing each one.

**App identity for this tool:**
- Personality: technical, sharp, diagnostic — built by a developer, for developers.
  Same family as PhoneCheck's "X-ray of your device" feel, not FMN's warmth.
- Working tagline direction: something in the vein of "Catch it before they do" or
  "Know before you ship" — functional, confident, no fluff. Not finalized; feel free
  to suggest, but keep it short and direct, per the brand's voice rules below.

**Colors (exact hex, from the official brand guide):**
- Background: Studio Black #1A1A1A (primary) / Pure Black #000000 (deep
  backgrounds, cards)
- Primary accent: Electric Green #00FF88 — buttons, highlights, active states,
  the "Start Recording" / "Replay" buttons
- Text: White #FFFFFF primary, Dim White #888888 secondary/descriptions
- SKR/crypto-specific actions ONLY: Purple #9945FF — this applies specifically to
  the SKR devnet transaction buttons ("Send Test Transaction," "Send Large
  Transaction"), per the brand rule that purple is reserved for Solana/SKR actions
- Destructive/error states: Red #FF4444 — fits the "flow changed"/break-detected
  state on the results screen
- Success/confirmed states: Deep Green #006633

**Typography:** sans-serif bold for headers, sans-serif-light for body text,
monospace for stats/data — the replay log and results screen are a natural fit for
the monospace "stats bar" style (dark semi-transparent background, green monospace
text, | separators). Concrete live reference (PhoneCheck's actual device-report
screen, from sashystudios.com):
── DEVICE ──
Model: Solana Seeker
Android: 16 (API 36)
── DIAGNOSTICS ──
✅ Temperature Normal: 37°C
✅ Battery Health: Good
Section headers in ── LIKE THIS ──, checkmark-prefixed status lines — this is the
pattern to follow for the results screen's pass/fail output (e.g. ✅ Connect
Wallet: matched, ❌ Approval screen: not found — flow changed).

**UI patterns to reuse:** rounded-corner buttons (electric green primary, dark
#333333 secondary, purple for SKR actions), cards at #111111/#1A1A1A with
#333333 1dp borders and 12dp corner radius, the subtle green glow effect
(#00FF88 at 15-25% opacity) on active/focused elements if it's cheap to add —
skip it if it adds real time.

**Voice:** direct, confident, no corporate fluff — avoid "revolutionary," "world
class," "simple and easy." Describe what the tool actually does plainly.

**The S mark:** if there's time near the end (day 10, alongside the demo
video/pitch deck polish), a small electric-green S mark on the main screen or
splash would reinforce the Sashy Studios identity — not a must-have, cut first if
time is short.

## Future ideas — explicitly OUT of v1, do not build now
- **Editable recordings** — instead of re-recording a whole flow from scratch when
  a small feature gets added, let the user append new steps to the end of an
  existing recording, or insert steps mid-flow. Mid-flow insertion is the harder
  version: it requires replaying up to the insertion point, switching into record
  mode to capture the new steps, then resuming replay of the rest of the original
  recording — and the remaining original steps may no longer be valid if the new
  feature changed app state/navigation. Append-only (new steps added to the end)
  is the simpler, safer version of this and avoids that problem. Real product
  concern: full re-record on every small change is a genuine adoption/retention
  risk for a real testing tool — worth mentioning as v2 roadmap in the pitch deck,
  but not worth building before the hackathon deadline.
- **Continuous/background testing** — a version that runs persistently and
  auto-warns on regressions, rather than the current manual "hit replay when you
  want to check" model. Bigger build (persistent Accessibility Service, a trigger
  mechanism for when to re-test, notifications, battery/permissions handling).
  Not v1.

## Status and decisions as of Oct 4 (deadline Oct 8)

Where this section conflicts with earlier text in this file, this section wins.

### Verified on the Seeker
- Accessibility service enables, the self-click filter works, and both safety exits fired correctly: volume-down stop and the 60s watchdog.

### NOT verified, do not describe as working
- Replay has never run successfully on the device. The first recording test captured 0 steps. After the touch exploration change, one Settings row was captured, with no text and no resource ID.
- The countdown compiles but has not been tested on the device.

### Touch exploration finding
- While recording, touch exploration makes single taps only select, double-tap does not click, and one-finger swipes do not navigate. What looked like a freeze was this behavior, not a crash. A two-finger tap does click.
- That is unacceptable as the recording experience. Requirement: while recording, one-finger touches (tap, swipe, navigate) must behave normally.
- The capture method is under review. Candidates: (0) find out why ordinary taps do not emit TYPE_VIEW_CLICKED on API 36, since this may be a config problem; (A) TouchInteractionController; (B) transparent accessibility overlay with dispatchGesture re-dispatch; (C) guided step capture from the accessibility tree; (D) scripted step list for the demo app, with live recording shown as roadmap.
- Cutoff: if one-finger record and replay of a single tap is not reliable by end of Oct 5, switch to option D. Say so explicitly, do not drift.

### Capture method: resolved (Oct 5) - the Compose limitation, and the decision
Option 0 from the candidates above is answered. Verified two ways: (1) a TEMPORARY
"experiment" recording mode (touch exploration never requested) showed real apps built
with classic Android Views - `com.sashystudios.camcheck` - emit `TYPE_VIEW_CLICKED` for
ordinary one-finger taps with touch exploration off, while the demo app (then Compose)
emitted zero despite clearly handling the taps (window content kept changing). (2) Pulled
both APKs read-only and inspected their dex: CamCheck has 0 `androidx/compose/runtime`
references and 99 app-authored `res/layout/*.xml` files (classic Views, confirmed); the
demo app had 23 Composer references and 5 ComposeView references with zero app-authored
layouts (Compose, confirmed). Root cause: classic `View.performClick()` calls
`sendAccessibilityEvent(TYPE_VIEW_CLICKED)` directly; Compose's accessibility delegate
only emits that event when `ACTION_CLICK` arrives through the accessibility API, which is
what touch exploration provided and an ordinary untouched-exploration tap does not.

Decision: the demo app (module `app`) is rebuilt with classic Android Views instead of
Compose - `setContentView` + `activity_main.xml`, stable IDs `btnConnectWallet`,
`btnSendTest`, `btnSendLarge`, `statusText` - specifically so it emits `TYPE_VIEW_CLICKED`
on its own. The tester's recording path now never requests touch exploration at all - the
"Record (normal touch, experiment)" button's behavior became the only/main Record button;
the old two-finger flow and its touch-exploration request are removed. `MainViewModel`,
`TokenProgram`, `SolanaConfig`, and all MWA/SKR/transaction code are unchanged - only the
UI layer (Compose -> classic Views) and how its state gets collected (`collectAsState` ->
`lifecycleScope`/`repeatOnLifecycle`) changed in the demo app.

Guided capture (option C: no touch interception, a hardware key or floating button lists
clickable elements from the accessibility tree to pick from) is NOT being built now - it
goes on the roadmap for apps we don't control, which may still be Compose-based in the
real world (the demo app is ours, so switching its UI framework was the direct fix here;
a real third-party target app can't be rebuilt the same way).

### Status pill (built, on-device verification pending)
A display-only floating pill, TYPE_ACCESSIBILITY_OVERLAY, plain Android views. Top-center under the status bar. Black at 55% opacity, fully rounded, green #00FF88 monospace text, small green S at the left, soft green glow at about 20% opacity, pulsing dot every 1.5 seconds. FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE so touches always pass through, plus IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS on the whole view tree so touch exploration's hit-test skips the pill entirely instead of landing on it. One line while recording ("REC - 3 steps"); the detail line (last step/auto-stop/VOL-DOWN STOP) only shows for ~2s after each newly captured step. States: countdown "STARTING IN 5"; recording "REC - 3 steps" plus line 2 "last: <label or (unlabeled)> | auto-stop 0:42 | VOL-DOWN STOP"; stopped "STOPPED - 3 steps" fading after 3s; replay "REPLAY - step 2/5"; break in red #FF4444 "FLOW CHANGED - step 3". Remove the overlay when the service disconnects, recording is cancelled, or the watchdog fires. Safety rule: the pill must always end up fully visible or fully removed, never stuck invisible.

### Watchdog (redesigned - not yet re-verified on-device)
Changed from the flat 60s timer described below (that version is what the "Verified on the Seeker" bullet above actually tested) to two limits, whichever is hit first: an inactivity timer that resets to a fresh 90s every time a step is captured, force-stopping only once 90s pass with nothing captured; and a hard cap that force-stops 5 minutes after recording started no matter what, regardless of activity. The pill's auto-stop line shows whichever limit is closer and jumps back up when a step resets the inactivity timer. The force-stop path itself is unchanged: the pill is hidden (no "STOPPED" message) and the existing stop/touch-exploration-off logic runs, same as before and same as volume-down uses. Needs a real on-device pass to confirm the inactivity reset actually happens on each captured step and the hard cap fires at 5 minutes.

### Replay/pill fixes (Oct 4, not yet re-verified on-device)
- Package visibility: tester's manifest had no `<queries>`, so `getLaunchIntentForPackage` returned null for installed-but-invisible targets indistinguishably from "not installed" - this is what broke the SeekShot replay. Added `<queries>` entries for the demo app and SeekShot (wallet packages deliberately not added yet - Phantom is `app.phantom` on this Seeker, Solflare isn't installed). `replay()` now stops immediately and logs clearly on a launch failure instead of falling through to search the wrong window.
- Pill no longer gets stuck: a replay break now fades after ~8s (was permanent); a clean replay finish shows "REPLAY OK" for ~3s then fades; starting a new countdown/recording/replay clears any leftover pill state immediately instead of risking a stale fade-timer hiding the new content out from under it.
- Added TEMPORARY `Log.d` at the top of `onAccessibilityEvent` (tag `RecordingA11yService`) logging event type, package, and whether `event.source` is null - for the option-0 question from the capture-method investigation (does Compose emit `TYPE_VIEW_CLICKED` on plain taps the way classic Views do). Remove once that's answered.

### Test rules
- I run any risky on-device test by hand. You may build with gradlew and install with adb, but never start the accessibility service via adb settings put (I enable it in Settings so the confirmation dialog appears), and never tap anything on the phone unless I ask.
- Replay test target: the demo app's Connect Wallet button (classic View now, resource ID `btnConnectWallet` - see the Oct 5 capture-method decision above). Settings rows record with no text or ID, so replay cannot find them. Close the wallet picker without selecting a wallet.
- Never connect my real wallet. The devnet test wallet and the SKR mint are not finished. The setup script is waiting on devnet SOL for the mint authority.
- If the screen locks up: hold Power for about 10 seconds.

### STRETCH, only after one-finger record and replay is proven
- Per-app saved recordings: one small JSON file per target package in app storage, plus a simple list screen where I pick an app and press Replay. Also fixes the current problems that a second recording overwrites the first and recordings are lost if the tester process dies. No cloud, no database, no multiple flows per app.
- Full version (named flows, several per app, segments) goes in the pitch deck roadmap only.

### Git
- Repo is pushed: github.com/SashyStudios/solana-app-tester. Commit 9f0af0c (countdown) is local only. Commits use the GitHub noreply email. setup/skr-mint-authority.json must never be committed.

### Break explanations (idea from Josh)
When a replay step fails, say WHY in plain words, using observable differences only. Don't claim to know the code change.
- Minimal (belongs with break detection, do this one): on a failed step, report "expected <resourceId / label>, not found", list up to 5 clickable elements currently on screen, and flag the closest match (same class, similar position or text) as "possibly renamed".
- Roadmap (v2): store a snapshot of the screen's clickable elements at each recorded step, then diff against the replay screen to report missing, renamed, moved and newly appeared elements (for example an unexpected dialog).
- Out of scope: tying a break to a specific commit or code change.

### Pre-tap label fix (Oct 5, applied - not yet re-verified on-device)
Bug: a recorded step's text/contentDescription are read from event.source on TYPE_VIEW_CLICKED, which Android fires from View.performClick() only after the click handler already ran - so a self-toggling element (SeekShot's "AllShot" flipping to "✕ Cancel" when pressed) was recorded, displayed, and matched on replay using its POST-tap label. That's backwards: the step list, pill and replay log read the flow wrong, and for a resourceId-less step it made replay search for text that doesn't exist on screen yet (the tap that produces it hasn't happened during replay), falsely reporting "flow changed."

Fix: `RecordingAccessibilityService` keeps a label cache (`"packageName/resourceId"` -> previous/current `(text, contentDescription)`), populated only while `RecorderMode.RECORDING` and reset at the start of each session. `TYPE_WINDOW_STATE_CHANGED` (a new screen) seeds it with a full tree walk of clickable nodes - deliberately the heavier path, since screen transitions are rare. `TYPE_WINDOW_CONTENT_CHANGED` (fires far more often) updates only the single node named by `event.source`, no tree walk. On `TYPE_VIEW_CLICKED`, the step is built with `preTapText`/`preTapContentDescription` read from the cache's `previous` slot (the value seen just before this element's own self-triggered content-change, if any), falling back to `current` if there's no prior distinct value.

`RecordedStep.primaryLabel()` is the one name shown anywhere a person reads it (step list, pill line 2, replay log, replay's fallback text match) - pre-tap label, else resourceId, else "(unlabeled)". It deliberately never falls back to the post-tap text/contentDescription. `postTapDetail()` surfaces that value only when it differs from `primaryLabel()`, shown only in the step list, clearly marked ("shows '<text>' after tap").

TEMPORARY `Log.d` (tag `RecordingA11yService`) added for each captured step while recording: resourceId, preTapText, postTapText - to confirm the cache is actually recovering the right value on a real toggle button. Remove once verified. The existing per-event diagnostic log line is unchanged.

Needs an on-device pass against SeekShot's AllShot button specifically: confirm the step list shows "AllShot" (not "✕ Cancel") as the primary label, the secondary detail appears correctly, and replay matches/clicks it without a false break.

Capture logic is necessarily touched (that's where the bug lives). The watchdog's timing/force-stop logic, volume-down's handling, touch exploration (`setTouchExplorationRequested`, still only ever called with `false`), and all MWA/SKR/transaction code are untouched.
