# solaris-android — house rules

This repo is the **Android app** for Solaris: a TWA (Trusted Web Activity)
wrapper of the Solaris PWA at `https://chat.dopp.cloud`, plus (Phase 3) native
Kotlin home-screen widgets and quick-settings tiles. The **server support**
(`/.well-known/assetlinks.json`, device-token) lives in `mdopp/solarisbay` — see
`docs/CONTRACT.md`.

These rules apply to every session, human or agent.

## Commits

- **Conventional Commits**: `type(scope): subject` — `feat`/`fix`/`refactor`/
  `chore`/`docs`/`test`. Scope mirrors the area: `feat(twa):`, `feat(widget):`,
  `feat(tile):`, `chore(ci):`, `docs:`.
- **No parentheses in the subject** beyond the conventional `(scope)`.

## Never commit secrets

- **NEVER** commit signing keystores or Play credentials. This includes
  `*.jks`, `*.keystore`, `*.keystore.base64`, `local.properties`, and any Play
  Console service-account JSON. They are:
  - kept **local** on the machine that generated them, and
  - injected into CI via **secrets** (`ANDROID_KEYSTORE_BASE64`,
    `ANDROID_KEYSTORE_PASSWORD`).
- The keystore is not recoverable if lost — back it up **outside** the repo.
  Losing the upload key means a Play key reset.

## The contract with solarisbay

- The app's **signing SHA256 certificate fingerprint** must be fed back to
  solarisbay's assetlinks config (env `ANDROID_CERT_FINGERPRINTS`) so
  `https://chat.dopp.cloud/.well-known/assetlinks.json` lists it. This is the
  Digital Asset Links **domain-verification handshake** — without it the TWA
  shows a URL bar. See `docs/SETUP.md`.
- The app targets **`https://chat.dopp.cloud`**. Auth rides the **Chrome Custom
  Tab cookies** = the existing **Authelia** session. No separate mobile login.
- Phase-3 native surfaces use a **device-token** (`sol_device_…`) instead of the
  browser cookies. Contract in `docs/CONTRACT.md`.
- Any change to package id, fingerprints, or the token contract is a
  **cross-repo change** — coordinate with solarisbay.

## Releases

- Play release = a **signed AAB** uploaded via **Play Console**. This is
  **human-gated** (Internal testing for family use; no automated publish).
- The debug/unsigned build in CI only proves the project *compiles* — it is not
  a release.

## Build

- **Bubblewrap** (Node CLI) for the TWA, or **Android Studio** for native work.
  Bubblewrap auto-downloads its own JDK + Android SDK.
- Scaffolded Gradle outputs (`/app/build/`, `.gradle/`, `build/`) are
  **gitignored** — only `twa-manifest.json` + native source are versioned.

## Verify

- An Android app **cannot be box-verified** like solarisbay. It is verified on a
  **device or emulator** (install the APK, check it launches full-screen with no
  URL bar, notifications arrive, widgets render). If you can't run a
  device/emulator, say so explicitly — don't claim a green build is a verified
  feature.
