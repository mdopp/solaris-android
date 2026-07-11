# SETUP — scaffold, sign, build, ship

The exact recipe a fresh Android session follows to go from `twa-manifest.json`
to an installable Solaris app. Run all commands from the repo root.

> **Dependency on solarisbay:** the manifest points at
> `https://chat.dopp.cloud/static/icon-512.png` and `icon-512-maskable.png`.
> Those icons + `manifest.json` are being added to solarisbay's `/static`. If
> Bubblewrap can't fetch them, that endpoint isn't live yet — coordinate with
> solarisbay before blaming the build.

## 0. Prerequisites

- **Node ≥ 18** (for the Bubblewrap CLI).
- Bubblewrap **auto-downloads its own JDK + Android SDK** on first run — you do
  *not* need a system Android SDK. (Alternatively, open the scaffolded project in
  **Android Studio**.)
- `keytool` (ships with any JDK) if you generate the signing key by hand.

```bash
npm i -g @bubblewrap/cli
```

## 1. Scaffold the Gradle project from the manifest

```bash
bubblewrap init --manifest ./twa-manifest.json
```

This scaffolds a full Android Gradle project *in place* (Bubblewrap reads
`twa-manifest.json`). The generated `app/`, `build/`, `.gradle/` etc. are
**gitignored** — only the manifest + native source are versioned.

## 2. Generate the signing key — KEEP IT OUT OF GIT

Bubblewrap will offer to create a keystore during `init`. Otherwise:

```bash
keytool -genkeypair \
  -keystore android.keystore \
  -alias solaris \
  -keyalg RSA -keysize 2048 -validity 10000
```

- `android.keystore` and `local.properties` are **gitignored** — confirm they
  never get committed.
- **Back the keystore up outside the repo.** If you lose it you cannot ship
  updates under the same upload key.

## 3. Get the SHA256 fingerprint

```bash
keytool -list -v -keystore android.keystore -alias solaris | grep SHA256
# or:
bubblewrap fingerprint
```

Copy the `SHA256:` value (the `AA:BB:CC:…` hex, colon-separated).

## 4. Feed the fingerprint to solarisbay (domain-verification handshake)

Set the fingerprint on the Solaris pod so
`https://chat.dopp.cloud/.well-known/assetlinks.json` returns it:

- solarisbay env: **`ANDROID_CERT_FINGERPRINTS`** = the SHA256 value(s),
  comma-separated if more than one (e.g. upload key **and** the Play App Signing
  key — see step 7).

Verify it's live:

```bash
curl -s https://chat.dopp.cloud/.well-known/assetlinks.json | jq .
```

Without this handshake the TWA opens **with a URL bar** (unverified). See
`docs/CONTRACT.md` for the exact JSON shape.

## 5. Build

```bash
bubblewrap build
```

Produces:

- a **signed APK** — sideload for quick device testing, and
- a **signed AAB** (`app-release-bundle.aab`) — for Play.

Install the APK on a device/emulator and confirm: launches full-screen (no URL
bar once assetlinks is live), Authelia login works in the Custom Tab,
Web Push notifications arrive.

## 6. Play Console — Internal testing

- Create the app in **Play Console** ($25 one-time developer registration).
- Upload the **AAB** to the **Internal testing** track. Internal testing has
  **no public review** — fine for family use.
- Add testers by email; they install via the opt-in link.

## 7. Play App Signing vs upload key (important)

- The keystore you made in step 2 is your **upload key** — it signs what you
  upload.
- Play **re-signs** the app with a separate **Play App Signing key** that Google
  holds. The certificate users actually verify against is the **Play App Signing
  key**, not your upload key.
- Therefore `ANDROID_CERT_FINGERPRINTS` (step 4) must eventually include the
  **Play App Signing SHA256** (found in Play Console → *App integrity*) as well
  as (or instead of) the upload-key fingerprint. For sideload/internal builds
  the upload-key fingerprint is what verifies; for Play-distributed installs it's
  the Play App Signing key. List **both** to cover both distribution paths.
