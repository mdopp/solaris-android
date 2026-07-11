# solaris-android

The **Android side** of the Solaris mobile epic
([solarisbay#718](https://github.com/mdopp/solarisbay/issues/718)).

This repo holds the **TWA (Trusted Web Activity)** wrapper of the Solaris PWA
(`https://chat.dopp.cloud`) and, later, native Kotlin **home-screen widgets and
quick-settings tiles**. It is the *app + build* — nothing here runs on the
household box.

## Two-repo architecture

Solaris on Android is split across two repositories that must stay in sync:

| Repo | Owns |
| --- | --- |
| **`mdopp/solaris-android`** (this repo) | The Android app + build: TWA wrapper (Bubblewrap), native widgets/tiles, CI, Play packaging. |
| **`mdopp/solarisbay`** | The **server support**: the `/.well-known/assetlinks.json` endpoint (Digital Asset Links, for domain verification) and the Phase-3 **device-token** minting/acceptance. |

The seam between them is documented in [`docs/CONTRACT.md`](docs/CONTRACT.md).
Any change to those endpoints is a **cross-repo coordination** — you cannot ship
the app change alone.

## Why a TWA (and not a raw WebView)

A TWA runs the site in the **real Chrome engine** (Custom Tabs), not a stripped
WebView. That buys us, for free:

- **Web Push notifications already work inside a TWA** — the same VAPID/Web-Push
  path the PWA uses in-browser. Notifications ship *with the wrapper*; no FCM,
  no native push code required.
- Full Chrome cookie jar → **auth rides the existing Authelia session** in the
  Custom Tab. No separate mobile login.
- No URL bar / browser chrome once Digital Asset Links verification passes.

## Milestone phases

- **Phase 2 — TWA / Play** ([solarisbay#716](https://github.com/mdopp/solarisbay/issues/716))
  Wrap the PWA with Bubblewrap, verify the domain via assetlinks, sideload +
  ship to Play Internal testing. Web Push works out of the box.
- **Phase 3 — Native widgets / tiles + device-token**
  ([solarisbay#717](https://github.com/mdopp/solarisbay/issues/717))
  Kotlin `AppWidgetProvider`/`RemoteViews` home-screen cards, a chat-entry
  widget, and a `TileService` quick-settings tile. These live *outside* the
  browser and can't ride the Custom Tab cookies, so they authenticate with a
  **device-token** minted by solarisbay.

## Getting started

- [`docs/SETUP.md`](docs/SETUP.md) — the exact recipe: Bubblewrap scaffold,
  signing key, fingerprint → solarisbay, build, Play upload.
- [`docs/CONTRACT.md`](docs/CONTRACT.md) — the interface with solarisbay.

The Bubblewrap config lives in [`twa-manifest.json`](twa-manifest.json). Build
outputs (the scaffolded Gradle project, `build/`, APK/AAB) are **gitignored** —
only the config + native source are versioned.
