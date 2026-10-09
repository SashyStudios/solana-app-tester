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
- Shareable: results copyable as plain text (done, see Status - "Copy report" button).
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
- **Extend the label cache to elements without a resourceId** (key on className plus
  position) so they get real pre-tap tracking, instead of falling back to post-tap
  text as a last resort for matching only (see the Oct 5 pre-tap label fix follow-up).
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
- **Modular flows (Josh)**: record each screen or section as its own module, then
  chain modules into one full replay. Chaining requires each module to declare its
  expected start screen and the tool to verify it before running, and requires
  skipping the clean launch for modules after the first. Cheap first step: run
  saved flows in sequence without a fresh launch between them. This builds on
  multiple saved flows per app.

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
- Full version of saved flows (named flows, several per app, segments) goes in the pitch deck roadmap only - see Future ideas. Per-app saved recordings themselves are done, see Status below.

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

### Pre-tap label fix, follow-up: resourceId-less steps regressed (Oct 5, applied)
On-device test against SeekShot found the fix above introduced a real regression: a 7-step recording replayed only 3 steps before a false break. Diagnosed read-only (logcat has no record of replay results - `RecorderBridge.appendLog()` only updated an in-memory `StateFlow`, nothing reached logcat before this fix; the actual data came from a passive screenshot of the tester's own still-open Replay Log). Step 4 broke with `resourceId=null, searched text=null` - `findNode()` had nothing to match on at all, because the label cache is keyed exclusively by resourceId (`observeLabel()` is only ever called when one exists), so a resourceId-less element can never get a `previous`/`current` entry and `preTapText`/`preTapContentDescription` are `null` by construction for every such step. Before the pre-tap fix, `findNode()`'s fallback used `step.text` (post-tap) directly, which was at least non-null for any element with *some* text - the fix replaced that with a value guaranteed absent for this whole category of element, silently disabling their fallback entirely.

Fix: `findNode()` gained a third tier - resourceId, then pre-tap text, then (new) post-tap text/contentDescription as a last resort, only reached when neither of the first two found a match. The break-detection log's "searched text=" now reflects the same three-tier expression, so it never again claims "searched text=null" when a match was actually attempted. When a step matches via the new third tier, replay logs "Step N/M: matched by post-tap text '<text>' (no pre-tap label recorded)" so a false match is visible in the log, not just silently accepted. None of this changes what's shown to a person as the step's name - `primaryLabel()` (step list, pill, the "tapped" log line) is unchanged: pre-tap label, else resourceId, else "(unlabeled)", never post-tap.

`RecorderBridge.appendLog()` now also writes every line to `Log.d` (tag `TesterReplayLog`), so replay results survive in logcat even if the tester's own UI state is gone - this gap (no logcat record at all) is what made the original investigation need a screenshot instead of a log pull.

Needs an on-device re-run of the same SeekShot flow to confirm step 4 (or whichever step has no resourceId) now matches via the post-tap fallback and logs the new "matched by post-tap text" line, and that replay reaches further than 3/7.

### Replay variance between runs (Oct 5, applied)
Same 7-step SeekShot flow gave different results run to run (1/7, 3/7). Investigated read-only: `TesterReplayLog`/`RecordingA11yService` logcat had nothing - not a bug, the main ring buffer is only 256 KiB and SeekShot's own system-level logging (window manager, activity lifecycle) alone produced 656 lines over about an hour of testing, almost certainly rotating the actual per-step/replay data out before it could be read. Two causes identified from source instead, both now fixed:

- Replay's launch intent used only `FLAG_ACTIVITY_NEW_TASK` - if SeekShot's task already existed from a previous attempt, it was brought to the foreground in whatever state that attempt left it in, not from a consistent starting point. Now also sets `FLAG_ACTIVITY_CLEAR_TASK`, forcing the target back to its launch screen every time. Log line says exactly what that does and doesn't reset: "Launching \<package\> with a clean task (activity stack reset; saved data and permissions are not reset)" - the process may be reused rather than killed, and SharedPreferences/databases/files/granted permissions are untouched.
- `findNode()` was a single immediate lookup with no wait - if the screen a step needs takes longer than the 1000ms step delay to finish rendering, that single check missed it and falsely broke, with timing that can vary run to run. Replaced with `findNodeWithRetry()`: polls every 300ms for up to 3s, covering both "no active window yet" and "window present but node not found" the same way. The existing 1000ms delay between steps is unchanged - the retry only extends how long a *single* step's lookup is given before giving up.

Also implemented the minimal break explanation from CLAUDE.md's "Break explanations" section: on a failed step, log "expected \<name\>, not found", up to 5 currently-visible clickable elements (resourceId + label), and the closest match (same class) flagged as "Possibly renamed." The on-screen snapshot is walked and converted to plain data (`ClickableElement`, no `AccessibilityNodeInfo` reference) before the root it came from is recycled, each poll attempt, so nothing is ever read from a recycled node.

Needs an on-device re-run of the same SeekShot flow to confirm: results are now consistent across repeated runs, a step that's merely slow (not actually broken) no longer falsely breaks, and a genuine break shows the new on-screen listing with a sensible closest-match flag.

### Copy report (Oct 6, applied)
A "Copy Report" button sits next to Replay. It copies plain text to the clipboard (`LocalClipboardManager`, no new permission) and shows a short "Report copied" confirmation (`Toast`): a header (target app package, date/time, Android release + API level), the recorded steps (number, name, package), and the full replay log. The "Recorded steps: N" line also got its own small "Copy" button that copies just the steps list in the same plain-text format - this was flagged as "if easy" and turned out to be a one-line reuse of the same formatting function, so it's in too.

The report's "App" line uses the first recorded step's packageName (the target app being tested), not the tester's own package - that's the package a developer pasting this into a bug report would actually want to see.

### Per-app saved flows (Oct 6, applied) - moved out of STRETCH
One saved flow per target app, replacing "a second recording overwrites the first and recordings are lost if the tester process dies" with a real save/load/delete cycle. No named flows, no multiple flows per app, no cloud, no database - exactly the STRETCH item's original scope, now built against the one-finger record/replay path once it was proven working.

- **Save**: `stopRecording()` (the one function every stop path - button, volume-down, watchdog - already funnels through) now saves the recording as JSON in the tester's own `filesDir` whenever it stops with at least one step. One file per target package (`flow_<packageName>.json`); re-recording the same app overwrites its file. New `SavedFlowStore` (`model/SavedFlow.kt`) owns the JSON read/write - built on `org.json` (already part of the Android SDK, no new dependency), not a database.
- **Which package is "the target app"**: the most common `packageName` among the recorded steps, after excluding the device's current home/launcher app (resolved dynamically via `PackageManager.resolveActivity` on a HOME intent, not hardcoded, since the launcher package varies by device/OEM) and `com.android.systemui`. Could a launcher tap at the start confuse this? Only in the edge case of a very short recording - a single "tap the icon to open the app" step would only ever contribute one vote, so even without excluding the launcher it would rarely out-vote a real flow's many target-app taps; excluding it removes that edge case outright. It's still a frequency heuristic, not a guarantee - a recording that bounces through several non-launcher screens (e.g. an app-drawer search) before reaching the target could still be misread.
- **Null fields**: every `RecordedStep` field that can be null is omitted from the JSON object entirely when null (rather than writing a JSON `null`) and read back via a small `has(name)` check - round-trips correctly either way.
- **Format version**: every file carries `formatVersion: 1` so a future format change can recognize and handle (or deliberately reject) files written by this version instead of guessing.
- **Load**: `MainActivity.onCreate` calls `RecorderBridge.loadSavedFlows(this)` - pure file I/O via `Context`, independent of whether the accessibility service is connected (enabling it is a separate manual step). A file that fails to parse is skipped, not crashed on.
- **UI**: a "Saved flows" list sits directly below the Record button - app name (from `PackageManager`, falling back to the raw package name if it can't resolve, e.g. the app was since uninstalled), step count, saved time, "No saved flows yet" when the list is empty. Tapping a row loads it as the current in-memory recording (there's no separate "active flow" field - the active flow is just whichever saved flow's package matches what's currently loaded, same thing Replay/the step list/Copy Report already read from) and highlights it in green; tapping is disabled while recording/replaying. Each row has its own "Delete" with a confirmation dialog before anything is removed. Sashy styling throughout (`CardBlack` cards, `BorderGray`/`ElectricGreen` borders, 12dp corners, monospace labels).
- **Package visibility**: replay needs to be able to launch *any* saved app, not just the two currently hardcoded in the manifest's `<queries>`. Added a `<queries><intent>` block with a MAIN/LAUNCHER action+category filter, which makes every ordinary launchable app visible to this tool's `PackageManager` calls (`getLaunchIntentForPackage` and friends) without needing the restricted `QUERY_ALL_PACKAGES` permission. This is the standard, Play-Store-safe mechanism for "see any app the user can launch," stable since Android 11 (API 30) introduced package visibility - I'm not aware of anything API 36 changes on top of it, but haven't verified that against a live PackageManager response on-device. The two existing `<package>` entries are now redundant for apps with a launcher icon but are left in as harmless documentation of what this tool currently targets.

Needs an on-device pass: record a flow against SeekShot, confirm it saves and shows up in the list after a fresh launch, re-record to confirm overwrite, select/Replay/delete, and confirm replay can still launch SeekShot via the new `<queries>` entry.

Capture logic, the watchdog's own timing/trigger logic, volume-down's handling, touch exploration, and all MWA/SKR/transaction code are untouched - the only shared function touched by this is `stopRecording()`, which gained a save-on-stop side effect but no change to when/why any stop path calls it.

### Saved SeekShot flow read, read-only (Oct 6)
Read the saved flow file directly (`adb shell run-as com.clockin.apptester cat files/flow_com.sashystudios.seekshot.json`) to answer a question about steps 4-8 of the 9-step recording. All five are plain `android.widget.TextView` with only a `text` value ("Action", "Video", "Wide", "Wide", "Photo") - no resourceId, no contentDescription, no preTapText, immediately after two taps on `scenesButton`. Read as list items in a horizontal scene-picker strip: no resourceId because the item layout likely never assigns one (or the view is built programmatically, not inflated from XML); no contentDescription because a plain TextView's own text already satisfies TalkBack by default, so there was no accessibility reason to add one. `RecordedStep` has no bounds field, so "bounds if stored" doesn't apply - nothing was lost, there was never anywhere to put it. Steps 6-7 share the identical text "Wide" - can't tell from this data alone whether that's two distinct items with the same label, a RecyclerView recycling artifact, or a genuine double-tap.

### Unlabeled step warning (Oct 6, applied)
A step with no resourceId and no text/contentDescription in either pre-tap or post-tap form (`RecordedStep.hasNoIdentity()`) has nothing for `findNode()`'s three-tier match to go on at all - replay can only ride on step ordering plus whatever happens to be drawn at that position, not any real identity. None of the SeekShot flow's current steps actually trigger this (all have at least a post-tap `text`) - this is a general safety net, not a response to a defect found above.

Where it shows, all using the same shared wording (`List<RecordedStep>.unlabeledStepWarning()`, "⚠ N steps have no ID or label. Replay can't find them reliably. Add a contentDescription or resource ID to these controls."):
- A "⚠ " prefix before the affected step's label in the step list, in Copy Report's steps section, and in Copy's steps-only text.
- The full summary line under the step list (red, matching the brand palette's only warning-adjacent color) and in Copy Report, right after the steps section - not in the steps-only Copy text, which stays exactly the original per-step format.
- The pill's "last: <label>" line gets the same "⚠ " prefix when the most recently captured step has no identity.
- Logged once (not per-step) via `RecorderBridge.appendLog()` when recording stops, if any step qualifies.

One of these four touches a line inside `startRecordingStatusTicker()`, the function this file documents as owning the recording watchdog - flagging this explicitly since "don't touch the watchdog" was repeated as an instruction for this change. The edit is one line, changing only what string gets passed to `statusPill.showRecording()` for the last-step label; the watchdog's own timing and trigger logic (the inactivity reset, the hard cap, the force-stop branch, the loop's cadence) is byte-for-byte unchanged. Flagging it rather than deciding silently that "display text" was an acceptable reading of "don't touch."

Capture logic, `findNode()`'s matching, the watchdog's timing/trigger logic, volume-down, and touch exploration are otherwise untouched; no MWA/SKR/transaction code touched.

### Multiple saved flows per app (Oct 6, applied)
Checked first (read-only) whether saved flows survive installs: every install this session used `adb install -r` (never an uninstall), and the app's data directory's own timestamp (Oct 2) predates this session, which an uninstall/reinstall would have reset - saved files have not been wiped by anything I've done.

Changed per-app save from one overwritable file to one file per recording, so re-recording an app no longer loses the previous take:
- `SavedFlowStore.save()` always creates a new file, `flow_<packageName>_<savedAtEpochMillis>.json` - never replaces an existing one, regardless of how many already exist for that package. Nothing is ever auto-deleted; only an explicit, confirmed per-row delete removes a file.
- `SavedFlow` gained `id` (`"<packageName>_<savedAtEpochMillis>"`, derived from JSON content) and `fileName` (the actual file it came from). `loadAll()` doesn't care what a file is named, only what's inside it - a file saved under the old one-file-per-package naming (`flow_<packageName>.json`, from before this change) still loads correctly, it just never gets written again going forward. `fileName` is what `delete()` actually removes, since several flows can now share a packageName and deleting by package name alone would be ambiguous/wrong.
- "Active flow" is now a real field (`RecorderBridge.activeFlowId`, by `SavedFlow.id`) instead of being derived from a packageName match - with several flows per app, packageName alone no longer disambiguates which one is loaded. Starting a new recording clears it (an unsaved recording isn't any existing saved flow yet); saving one at the end of `stopRecording()` sets it to the newly-created flow; selecting a row sets it to that row; deleting the active row clears both it and the in-memory step list. Every other saved flow is left exactly as it was by all of these.
- **List UI**: grouped by app (one app's flows are never interleaved with another's), groups ordered by whichever app was most recently recorded, newest-first within each group. Each row reads `"<App name> #N | <step count> steps | <date time>"` - `#N` is that flow's ordinal within its own app's group, assigned oldest=1 upward by recording time, independent of the newest-first display order (`MainActivity.buildFlowDisplayList`).
- **Copy Report fix**: the step list's post-tap hint (`"(shows '<text>' after tap)"`, from `postTapDetail()`) was only ever shown on-screen - a step with a post-tap `text`/`contentDescription` but no pre-tap label still reported as flatly "(unlabeled)" in Copy Report/Copy steps, even though the on-screen list showed more. `buildStepsReportText()` now appends the same hint, so both surfaces say the same thing about the same step.

Needs an on-device pass: record the same app twice, confirm both takes show up as separate rows (not one overwriting the other), confirm the ordinal/grouping/newest-first ordering look right with 2+ apps and 2+ takes each, confirm delete only removes the one row tapped, and confirm a step with post-tap-only text no longer shows as bare "(unlabeled)" in Copy Report.

Capture logic, `findNode()`'s matching, the watchdog, volume-down, touch exploration, the status pill, and all MWA/SKR/transaction code are untouched.

### Main screen scroll fix (Oct 6, applied)
With two or more saved flows, the list below Record had no height cap and the outer Column had no scroll modifier, so once the list grew past the screen, Replay/Copy Report/the Replay log (which relied on `weight(1f)` to fill remaining space) got pushed off with nothing able to bring them back into view. Fixed: the outer Column now scrolls (`Modifier.verticalScroll`); the saved-flows list is a `LazyColumn` capped at ~3 rows (170dp) with its own internal scroll, same pattern the recorded-steps list (140dp) already used; the Replay log switched from `weight(1f)` (which doesn't work once the parent scrolls - a scrolling Column measures content at natural height, leaving no bounded space for weight to divide) to a fixed height (200dp) with the same internal-scroll pattern. Layout-only change in `MainActivity.kt`; capture/save/load/matching/watchdog/volume-down/touch-exploration/status-pill/MWA/SKR/transaction code untouched.

### Planned demo bug changed: rename instead of crash-on-rejection (Oct 6)
The "Planned demo bug (locked)" section above described a crash on tapping "reject" on the wallet approval screen. That needs a real devnet test wallet connected to demo, which doesn't exist yet (the devnet test wallet and SKR mint are still unfinished - see Test rules). Per this file's own rule that a later status entry wins over earlier text: the demo bug for now is a **resource id + label rename**, not a crash, and needs no wallet at all.

- Demo app (module `app`) gained a "Safe test actions (no wallet)" section: three buttons (Ping, Check status, Reset) with a local counter, entirely separate from `MainViewModel`/MWA/SKR. Two build flavors, `v1` and `v2` (same `applicationId`, so `adb install -r` installs either over the other) - in `v2` only, "Check status" (`btnCheck`) becomes "Run check" (`btnStatusCheck`): both id and label change, simulating a developer renaming a control between versions. `v1` is unchanged. Flavor is read via `BuildConfig.FLAVOR` (required enabling `buildFeatures.buildConfig` - off by default in this AGP version); a small `build: v1`/`build: v2` label at the bottom of the demo app's screen makes it obvious which one is currently installed.
- Recorded baseline: record a flow that taps "Check status" against `v1`. Replay against `v2` should flag it as a break - `btnCheck`'s resourceId is gone, replaced by `btnStatusCheck` with different text - and the tester's break explanation (see below) should surface "Run check"/`btnStatusCheck` as the (and only) rename candidate.
- **Crash-on-rejection moves to the roadmap**, to be restored once a real devnet test wallet exists for the demo. Still worth keeping as the "real" planned bug for the pitch deck narrative (visually unambiguous, realistic bug class, requires MWA-specific understanding) - just not buildable as a demo *today*.

Needs an on-device pass: record "Check status" against `v1`, confirm replay passes on `v1` again, install `v2` over it with `adb install -r`, confirm replay flags a break and the explanation names `btnStatusCheck`/"Run check" as the candidate.

### Break explanation: full-screen candidate search + "Last result" card (Oct 6, applied)
Two changes to break-explanation only - `findNode()`'s matching logic itself is untouched.

- **Closest-match search widened**: previously computed only over the (at most 5) elements already being shown, so a real rename candidate sitting just past that cut-off was invisible. `collectClickableElements`/`listClickableElements` are now uncapped (walk the whole on-screen tree); the "On screen:" line still truncates to a cap for display (`MAX_ON_SCREEN_ELEMENTS`, raised from 5 to 8), but candidate search runs over everything. A candidate is a same-class on-screen element not already "claimed" (by resourceId or by label) by any *other* step already in the recording - an element some other step expects is more likely to legitimately be that other control than a renamed version of the one that broke. Three outcomes, each worded differently so they're never confused: zero candidates ("No likely rename candidate found..."), exactly one ("Possibly renamed: ..."), several ("Candidates: ..." - lists them, doesn't guess which).
- **"Last result" card**: sits above the saved-flows list, hidden until a replay has ever run. Green "Replay passed: N of N steps (\<app\> #K)" or red "Step N of M failed" plus the same explanation line already going to the replay log. `RecorderBridge.lastResult` (a new `ReplayResult` - `Passed`/`Failed`) is set once at the end of `replay()`, reusing the explanation string already computed for the log rather than recomputing it. The "(\<app\> #K)" part isn't stored in the result - it's resolved in the UI from `activeFlowId` against the current saved-flows list, the same way the list's own green-highlight already works, so it reflects whichever flow is *currently* marked active rather than a snapshot frozen at replay time. Stays showing the latest result until a later replay overwrites it; nothing clears it early. Sashy styling (`CardBlack`/`BorderGray`/monospace), page and box scrolling unchanged from the prior fix above.

Needs an on-device pass: trigger a break with several equally-valid same-class elements on screen and confirm "Candidates:" lists more than one; confirm a clean replay's card reads "Replay passed: N of N steps (\<app\> #K)" with the right app/ordinal; confirm a failed replay's card matches what's in the replay log.

### MWA Stage 1: wallet connect only (Oct 6, applied - not yet verified on-device)
The tester itself is now an MWA dapp, not just a recorder of one. Connect/disconnect against
devnet only; **no transactions, no signing, no RPC calls in this stage**.

- **Dependencies**: `tester/build.gradle` gained the same five the demo app uses, same
  versions - `mobile-wallet-adapter-clientlib-ktx:2.2.0`, `rpc-core`/`rpc-solana`/
  `rpc-ktordriver:0.2.11`, `bitcoinj-core:0.17.1` (Base58). The three `rpc-*` artifacts are
  deliberately unused in Stage 1 and are there for the signing stage.
- **`INTERNET` permission added** to the tester manifest. It had none - the demo app does.
  Not needed for the MWA handshake itself (that's a local socket association), but the RPC
  client in the signing stage will fail without it, and discovering that later as a vague
  network error is worse than declaring it now. Verified present in the merged manifest.
- **`solana/DevnetConfig.kt`** (new): `RPC_URL`, `CLUSTER = Solana.Devnet`, `CLUSTER_LABEL`,
  and `requireDevnet(blockchain)` which **throws** on anything but `solana:devnet`. It
  compares `fullName` rather than object identity so a `Blockchain` built another way can't
  slip through. Called at the top of every wallet request, so it's enforcement, not a
  comment. Also `EXPECTED_TEST_WALLET` (currently
  `4BAHsk1dFpKmp1kuZyK8ziSsTgpqgjtGqXwK3Ppb8wF2`); an empty string disables the check.
- **`solana/WalletConnector.kt`** (new): a `MobileWalletAdapter` with
  `ConnectionIdentity(identityName = "Solana App Tester", identityUri =
  "https://clockin.hackathon/tester")` - deliberately distinct from the demo app's "MWA Test
  Target App"/`https://clockin.hackathon`, since both will appear separately in a wallet's
  authorization list and confusing the two mid-demo would be easy. Exposes
  `connection`/`requestInProgress`/`message` as `StateFlow`s, mirroring `RecorderBridge`'s
  shape. `connect()` returns the account's Base58 address plus the wallet's `accountLabel`
  (null when the wallet gives none - never invented). `disconnect()` calls the library's
  `disconnect`, which does the real `deauthorize` RPC, then clears `authToken` and local
  state in a `finally` either way: if the deauthorize failed the wallet may still hold the
  authorization, so the failure is reported truthfully while this app stops claiming to be
  connected. **Nothing retries automatically** - a failed or cancelled request ends and waits
  for a deliberate second tap, because an auto-retry would re-prompt the wallet for an
  approval nobody asked for twice.
- **Four distinct outcomes, each with its own message.** Timeout is a 30s
  `withTimeoutOrNull` wrapper (the library's own internal limits are 20s send-intent / 10s
  connect, so this is an outer backstop) and reads exactly: "No response from the wallet. If
  you use Phantom, check that Testnet Mode is set to Solana Devnet." No-wallet is the
  library's `NoWalletFound`. Cancel is only *claimed* when the underlying message actually
  indicates one - verified from the clientlib 2.2.0 sources, a cancel arrives as
  `RESULT_CANCELED` -> "Request was interrupted", or as the protocol's `ERROR_NOT_SIGNED` ->
  "User did not authorize signing". Anything else is passed through verbatim as "Wallet
  request failed: <raw>" rather than guessed at, so a real failure is never mislabelled as a
  deliberate cancel. `WalletMessage` carries an `isError` flag so a timeout can't render as
  quiet grey status text beside a success.
- **UI**: a "Wallet (devnet)" card sits between the Record button and the "Last result"
  card - Record stays the prominent primary action, and the card is still visible without
  scrolling (it needs to be, for demo video/screenshots). Purple `DEVNET` badge, full
  untruncated monospace address (a partial key is useless for checking which wallet you're
  on), wallet label line, Connect/Disconnect, and the red bordered banner "Not the configured
  test wallet. Disconnect before doing anything else." when `EXPECTED_TEST_WALLET` is set and
  the connected address differs. The banner warns and does not block - only the person
  holding the phone can tell a wrong-wallet mistake from a deliberate second test wallet.
- **Brand judgement call to review**: the Connect button and the badge use Solana purple
  `#9945FF`. The brand rule reserves purple for "SKR/crypto-specific actions" but then says
  it "applies specifically to the SKR devnet transaction buttons." A wallet connect is a
  crypto action but not a transaction, so this is a reading, not a given - easy to switch to
  Electric Green if that's wrong.
- **Mutual gating**: Record and Replay are disabled while `requestInProgress` is true (a
  wallet window is foreground then), and Connect is enabled only when recorder mode is
  `IDLE`. The accessibility service plays no part in this card and is never asked to touch a
  wallet window. The tester does not auto-tap or auto-approve anything in a wallet, by
  design and by absence of any code that could.
- **`ActivityResultSender`** is built in `MainActivity.onCreate` before `setContent`, as it
  must be (it registers an activity-result launcher, only legal pre-RESUMED), and passed into
  `TesterScreen`.

Capture logic, `findNode()`'s matching, the watchdog, volume-down, touch exploration, the
status pill, the saved-flows code, and the demo app are all untouched.

Build: `./gradlew :tester:assembleDebug` succeeds (needs
`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` from the shell). No new warnings -
the only ones are pre-existing `AccessibilityNodeInfo.recycle()` and `LocalClipboardManager`
deprecations. Installed with `adb install -r`; the service was not started and nothing was
tapped.

Needs an on-device pass: tap Connect and confirm which of the three installed MWA wallets
actually honours `solana:devnet` (`com.solanamobile.wallet` 1.17.0 /
`ag.jup.jupiter.android` / `app.phantom` 26.6.0 - Solflare is not installed); confirm the
address and label render correctly; confirm Disconnect deauthorizes; confirm the 30s timeout
message appears if Phantom is left on mainnet (its documented Android behaviour there is to
show nothing at all rather than return an error); and confirm the wrong-wallet banner fires
with a non-configured wallet.

### Protected-package guard, strict version (Oct 6, applied - not yet verified on-device)
Came out of a read-only privacy audit. The audit's findings that drove this: the service read
node text on every `TYPE_VIEW_CLICKED` in **every** mode (the discard happened later in
`RecorderBridge.appendStep`, so IDLE/COUNTDOWN/REPLAYING still read the text of every tap and
threw it away); nothing anywhere checked `isPassword`; and a wallet PIN pad built from
ordinary buttons would be captured digit by digit into a saved flow, the step list, logcat and
Copy Report, with step order being the PIN. Note `isPassword` would not have caught that case
anyway - those buttons aren't password fields - which is why this is a package guard, not a
node-property check.

- **`PackageGuard.kt`** (new): a fixed set - `com.solanamobile.wallet`,
  `com.solanamobile.seedvaultimpl`, `app.phantom`, `ag.jup.jupiter.android`,
  `com.android.systemui`, `com.android.keyguard`, `com.android.credentialmanager`,
  `com.google.android.gms`. Explicitly **not a security boundary**: an unlisted wallet is
  still fully visible. The README says so in those words.
- **Capture**: one early return at the very top of `onAccessibilityEvent`, before any
  `event.source` fetch and before anything is logged, so a protected package is never read,
  never recorded, and never even named in a log line.
- **Replay**: `findNodeWithRetry` checks `rootInActiveWindow.packageName` and, if protected,
  recycles and returns a new `ReplayLookup.Skipped` **immediately** - it does not poll out the
  3s timeout, since a protected window isn't a slow-rendering screen to wait for. `findNode`
  is never called and `listClickableElements` never walks the tree, so nothing is read; and
  because no node is ever returned from such a window, the `performAction(ACTION_CLICK)` call
  is unreachable for it. Logs "Step N/M: skipped - <package> is protected. The tester never
  reads or taps inside a wallet."
- **Skipped is a third outcome**, not a pass and not a break: counted separately, new
  `ReplayResult.FinishedWithSkips(completedSteps, skippedSteps, totalSteps)`, new pill states
  `showProtected()` ("WALLET SCREEN - SKIPPED") and `showFinishedWithSkips()` ("REPLAY DONE -
  N SKIPPED"), and a neutral grey "Replay finished with skipped steps" in the Last result card
  rather than green. `StatusPill.render`'s `isError: Boolean` became a three-value `Tone`
  (NORMAL/NEUTRAL/ERROR, the neutral being the palette's Dim White) specifically so a skip
  can't render in the normal green and read as "fine". Copy Report inherits all of it through
  the replay log, which already carries the skip lines.
- **Both TEMPORARY `Log.d` lines removed** (the per-event diagnostic and the per-captured-step
  one), their investigations being recorded as answered. `android.util.Log` is no longer
  imported by the service. `RecorderBridge.appendLog`'s `Log.d("TesterReplayLog", ...)` is
  unchanged and still the only logcat output.
- **Site-4 read gated**: the `TYPE_VIEW_CLICKED` handler now returns early unless mode is
  `RECORDING`, so the read surface matches what's actually used. `appendStep`'s own RECORDING
  check is left in place as a redundant second gate.

**This conflicts with must-have #3 and that conflict is now live.** Recognizing the four MWA
states (wallet connect dialog, approval screen, rejection, insufficient funds) requires
reading nodes inside the wallet app, which the strict guard forbids outright. Nothing is lost
today - no flow has ever successfully recorded a wallet screen - but #3 cannot be built while
this stands. The resolution, **deferred, not built**: split the two things the guard conflates
into (a) never click inside a protected package, no exception, (b) never persist or log nodes
from one, (c) may read transiently for recognition only, discarding the text. That keeps #3
buildable while still guaranteeing the tester never taps a wallet and never writes wallet text
to disk. Until that exists, the strict version stands.

**Known limitation of the strict version**: because events from protected packages are dropped
before anything is read, the tester cannot tell that a wallet is in the foreground *while
recording* - so the pill keeps showing "REC - N steps" and simply captures nothing. There is no
recording-time "WALLET SCREEN - NOT RECORDING" message, and there can't be one without
weakening the early return. The skipped-step pill/message exists on the replay side only.

Matching logic (`findNode`), saved flows, touch exploration handling, the watchdog, the
volume-down stop, and all MWA/signing code are untouched. Capture and replay were changed
under explicit authorization for this task.

Build: `./gradlew :tester:assembleDebug` succeeds. Installed with `adb install -r`; service not
started, nothing tapped.

Needs an on-device pass: record normally and confirm flows still capture; open a wallet while
recording and confirm nothing from it is captured and nothing appears in logcat for it; replay
a flow whose window is a protected package and confirm the skip line, the neutral pill, and the
neutral Last result card; confirm a clean replay still reads "REPLAY OK"/green.

### Guard fixes + privacy hardening (Oct 6, applied - not yet verified on-device)
Capture and replay changed under explicit authorization for this task.

- **Protected set** (Oct 9: `com.solflare.mobile` added): `com.android.systemui` removed (it owns the notification shade, quick settings and recents, so listing it made the tester go blind and, with the stop rule below, would abort recordings whenever the shade was pulled down). Added `com.debank.rabbymobile` (Rabby), `com.coinbase.android`, `org.toshi` (Coinbase Wallet's package id - taken from the well-known id, not confirmed against its on-device label). Found by `pm list packages` on the Seeker and deliberately NOT added: `com.solanamobile.dappstore`/`.updater`/`.rewards`, `com.stepfinance.solanafloormobile`, `com.aielabs.solai`, `app.seek.mobile`, and the many game/meme apps - none confirmed as wallets. Solflare, Backpack, Trust and MetaMask are not installed on this device, so they are not listed; add them if they ever are.
- **Lock screen** is detected with `KeyguardManager.isKeyguardLocked` (`PackageGuard.isKeyguardShowing`), not by package name - the keyguard UI is drawn by systemui on most devices, and `com.android.keyguard` may not exist as a separate package here (kept in the set anyway; unverified either way).
- **Recording start/stop**: recording is refused (checked at button press and again when the countdown ends, since the countdown is when the person switches apps) if the lock screen is showing or the foreground window's package is protected, with a neutral pill ("RECORDING NOT STARTED" + reason) and a log line. A recording in progress is stopped through the normal `RecorderBridge.stopRecording()` path (saves what was captured) when a protected package fires `TYPE_WINDOW_STATE_CHANGED` or `TYPE_VIEW_CLICKED`, or the keyguard shows on a window change. Only `event.packageName`/`eventType` are looked at before the early return - no node is fetched. This corrects the earlier "known limitation" note that said a recording-time wallet message was impossible without weakening the guard: it is possible from the package name alone. Consequence: the stop/skip messages DO log the protected app's package name; they never log anything from its screen. The earlier "never even named in a log line" claim no longer holds and the README was reworded to match.
- **Password nodes**: where the service reads text/contentDescription (label-cache snapshot, per-node content update, the click step, the replay on-screen listing), nodes with `isPassword` are skipped - never read, logged or saved. A tapped password field is still recorded as a step (class + resource ID) with null text/contentDescription. The `isPassword` limits stand: a PIN pad of ordinary buttons is not a password node.
- **Break explanation**: the "Also on screen" list leaves out elements with no id and a blank/"(unlabeled)" label, with a short count note so the list never silently under-reports. Candidate matching is unchanged (such elements can't share a word with a label anyway). The old "On screen:" line no longer exists since the three-line rework - "Also on screen:" is the only list.
- **WebView**: untested. Chromium exposes a page to accessibility as virtual nodes created on demand for a service, so text may be readable, but Android resource IDs may be missing and click events/matching inside one are unverified on this device. README Limits says so.

Unchanged: `findNode` matching, saved flows, the watchdog, volume-down, touch exploration handling, signing/MWA code.

### First-run notice + sensitive-label filter (Oct 9, applied - not yet verified on-device)
Capture and save changed under explicit authorization for this task.

- **First-run notice**: tapping Record when `tester_prefs` (private SharedPreferences) has no `first_run_notice_acknowledged` shows a dialog with the fixed text ("This tool can read what is on screen in other apps while recording. Don't record while entering passwords or payment details. Recordings stay on this device."). "Got it" stores the flag and only then calls `startRecordingCountdown()`; Cancel / tap-outside closes it without storing anything or starting anything. No countdown ever runs behind it. Never shown again once acknowledged.
- **Label filter**: every step is built through `buildScrubbedStep` (`model/RecordedStep.kt`), on both the normal path and the event-only path. `text`, `contentDescription`, `preTapText` and `preTapContentDescription` become null if they have 5+ digits in a row, look like an email, or contain a whitespace-separated token over 20 chars that is entirely hex or base58 characters. Resource IDs are never filtered. The removed value is never logged or kept (the in-memory label cache still holds raw values transiently, as before, and is cleared per session). Steps are marked "⚠ sensitive-looking label removed" in the step list, Copy and Copy Report.
- **Format note**: to make the marker survive save/load, `RecordedStep` gained `labelRemoved` (default false), written to JSON only when true and read with a default of false. This is one field beyond "null labels" and is flagged here deliberately; old files load unchanged and `formatVersion` is still 1.
- **README**: Limits and Privacy rewritten to match (password-field wording fixed, "taps seen but not recorded", auto-save/no review step, Order Online limit sentence, sensitive-label line); Roadmap gained the Save/Discard review, editable protected list and guided capture.
- Matching logic, the watchdog, volume-down, touch exploration and all MWA/signing code are untouched.
