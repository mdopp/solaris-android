# Solaris Android — Session Kickoff Brief

Paste this as the opening context for a fresh Claude session working in
**mdopp/solaris-android**. It gives the state, the exact step-by-step, the
cross-repo contract, and the gotchas so the Android work starts without friction.

## What you're building

`solaris-android` is the Android app for **Solaris**, a household AI assistant.
It wraps the existing Solaris **PWA** (`https://chat.dopp.cloud`) as a **Trusted
Web Activity (TWA)** — a thin, real-Chrome-engine wrapper — so it installs like a
native app while reusing the whole web UI. Later it grows **native Kotlin
widgets/tiles**. Web Push already works inside a TWA (real Chrome engine), so
notifications ship with the wrapper.

**Two-repo split (decided):** the SERVER side lives in `mdopp/solarisbay`; THIS
repo is only the Android app + build. Different toolchain (Gradle/Kotlin),
different release (Play Console), different secrets (signing keystore — never
committed).

## Already done on the solarisbay side (don't redo)

- `GET https://chat.dopp.cloud/.well-known/assetlinks.json` route exists, driven
  by the pod env `ANDROID_CERT_FINGERPRINTS` (currently empty → returns no
  fingerprint yet). **You produce the fingerprint that fills it.**
- Real PWA icons live at `/static/icon-192.png`, `/static/icon-512.png`,
  `/static/icon-512-maskable.png` (referenced by `twa-manifest.json`).
- Device-token endpoint for Phase 3 is a DRAFT PR in solarisbay (**#748**,
  security review pending) — needed only for native widgets, not the TWA.

## Environment

> **On the Solaris dev box this toolchain is ALREADY provisioned** (2026-07-11):
> `bubblewrap` is on `PATH` (npm user-global `~/.npm-global/bin`, via `~/.bashrc`),
> and JDK 17 + the Android SDK live in `~/.bubblewrap` (`bubblewrap doctor` → green).
> The repo is cloned at `/workspace/solaris-android`. **Skip the install** — run
> `bubblewrap doctor` to confirm, then go straight to Step 1 (`bubblewrap init`).
>
> On any OTHER machine, set it up first:

- Node is available. Bubblewrap (`@bubblewrap/cli`) is Node-based and can
  auto-download its own JDK + Android SDK (or use Android Studio). `npm i -g
  @bubblewrap/cli`.
- Android apps are **NOT box-verifiable** like solarisbay — test on a real device
  or an emulator.

---

## Step by step

### Phase 2 — TWA + Play (repo issues #1 → #3)

**Step 1 — Scaffold the TWA (issue #1).**
- `bubblewrap init --manifest ./twa-manifest.json` (packageId `cloud.dopp.solaris`,
  host `chat.dopp.cloud` are already set). It will prompt for the signing key —
  do Step 2 here.
- `bubblewrap build` → a debug/unsigned + a signed APK. Commit the generated
  Gradle project; build outputs + keystore are gitignored.

**Step 2 — Signing key (issue #2). NEVER commit it.**
- Generate `android.keystore` (Bubblewrap prompts, or
  `keytool -genkeypair -v -keystore android.keystore -alias solaris
  -keyalg RSA -keysize 2048 -validity 10000`). Store the keystore + passwords in
  a password manager + a backup — losing it means you can't update the app.
- Get the SHA256 fingerprint:
  `keytool -list -v -keystore android.keystore -alias solaris | grep SHA256`
  (or `bubblewrap fingerprint`). It's colon-separated hex, e.g. `AB:CD:…`.

**Step 3 — Feed the fingerprint back to solarisbay (the domain-verification handshake).**
- Set the pod env `ANDROID_CERT_FINGERPRINTS=<SHA256>` on the Solaris pod via
  ServiceBay (same kind of change as the VAPID keys — coordinate with the
  solarisbay/box operator session; it's an `install_template` with the FULL var
  set, then verify).
- Then `curl https://chat.dopp.cloud/.well-known/assetlinks.json` must return your
  package + fingerprint.

**Step 4 — Unblock public reachability (servicebay#2210).**
- `chat.dopp.cloud` is Authelia-gated (`forwardAuth: true`), so
  `/.well-known/assetlinks.json` currently 302s externally. Google's verifier is
  unauthenticated → it must bypass Authelia. That's **servicebay#2210** (a
  per-path forwardAuth exception for `/.well-known/`), done in the ServiceBay
  session. Without it the TWA can't verify and keeps the URL bar.

**Step 5 — Verify + sideload.**
- Install the signed APK on your phone. Confirm: **no URL bar** (assetlinks
  verified), Authelia login appears in the Custom Tab, and the notification
  permission prompt works (Web Push). If the URL bar shows, assetlinks isn't
  verified — re-check Steps 3+4 and the fingerprint match.

**Step 6 — Play Console (issue #3).**
- $25 one-time. Create the app, enable Play App Signing (upload key vs app
  signing key — keep the upload key = your `android.keystore`). Upload the **AAB**
  (`bubblewrap build` produces it) to **Internal testing** — no public review for
  family use.

### Phase 3 — native widgets/tiles (issues #4 / #5, later)

- Prereq: solarisbay **#748** (device-token) reviewed + merged. Native surfaces
  can't ride the browser cookies, so they auth with a `sol_device_…` bearer
  minted via `POST /api/device-tokens` from an authenticated session; store it in
  the app's secure storage.
- **#4** home-screen card widget (`AppWidgetProvider` + `RemoteViews`): render
  cards from `/api/portal/start?state_only=1`, act via `/api/ha/call` with the
  device-token, refresh via `WorkManager` (+ optional push nudge).
- **#5** chat-entry widget + quick-settings `TileService`.

---

## Contract with solarisbay (keep in sync — see docs/CONTRACT.md)

- **assetlinks:** `chat.dopp.cloud/.well-known/assetlinks.json`, package
  `cloud.dopp.solaris`, fingerprint from solarisbay env `ANDROID_CERT_FINGERPRINTS`.
- **device-token:** `sol_device_…` bearer, accepted as an alternative to the
  Authelia Remote-User header (Phase 3).
- **app target:** `https://chat.dopp.cloud/`, auth via Authelia in the Custom Tab.
- Any change to these is a cross-repo coordination.

## House rules / gotchas

- Conventional Commits (`type(scope): subject`, paren-free).
- **NEVER commit** the keystore, `*.jks`, `local.properties`, or Play
  credentials — CI secrets / local only (`.gitignore` already covers them).
- Android is device/emulator-tested, not box-verified.
- Coordinate the fingerprint + contract changes with the solarisbay session.

## Worklist

Issues **#1 → #5** in this repo, in that order. #1–#3 = the installable TWA;
#4–#5 = native, after #748 lands.
