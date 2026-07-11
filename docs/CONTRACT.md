# CONTRACT — the interface with solarisbay

The seam between this repo (the Android app) and `mdopp/solarisbay` (the server).
Both sides must agree on these; any change here is a **cross-repo coordination**.

## (a) Digital Asset Links — `assetlinks.json`

- **Endpoint:** `GET https://chat.dopp.cloud/.well-known/assetlinks.json`
- **Served by:** solaris-chat.
- **Auth:** **PUBLIC / unauthenticated.** Google's Digital Asset Links verifier
  fetches this itself and is not logged in — it **must not** sit behind Authelia.
  (This is the one path on `chat.dopp.cloud` that has to bypass the auth
  gate.)
- **Content:**

  ```json
  [
    {
      "relation": ["delegate_permission/common.handle_all_urls"],
      "target": {
        "namespace": "android_app",
        "package_name": "cloud.dopp.solaris",
        "sha256_cert_fingerprints": ["AA:BB:CC:..."]
      }
    }
  ]
  ```

- **Fingerprints** come from solarisbay env **`ANDROID_CERT_FINGERPRINTS`**
  (comma-separated → array elements). Populated from this repo's signing key —
  see `docs/SETUP.md` steps 3–4 and 7 (upload key + Play App Signing key).
- **Package name** must stay `cloud.dopp.solaris` (matches `twa-manifest.json`
  `packageId`). Changing it breaks verification on both sides.

Failure mode: mismatch/absence → the TWA renders **with a URL bar** (falls back
to a plain Custom Tab) instead of full-screen.

## (b) Device-token (Phase 3)

Native surfaces (home-screen widgets, the `TileService`) run **outside** the
browser, so they can't ride the Custom Tab cookies. They authenticate with a
device-token minted by solarisbay:

- solarisbay **mints** `sol_device_…` bearer tokens from an existing **Authelia
  session** (the user grants a device from a signed-in session).
- solarisbay **accepts** `Authorization: Bearer sol_device_…` as an
  **alternative to the `Remote-User` header** that the browser path relies on.
- Native widgets/tiles store the token on-device and send it on every request.

The mint/accept surface is owned by solarisbay; this repo consumes it. Token
format (`sol_device_…`) and the "bearer OR Remote-User" acceptance rule are the
contract.

## (c) App entry / auth

- The app loads **`https://chat.dopp.cloud/`** (`startUrl: "/"`).
- Auth happens via **Authelia inside the Chrome Custom Tab** — the same cookie
  jar as the browser PWA. No separate mobile login flow.

---

**Changing any of the above** (package id, fingerprint env, token format,
acceptance rule, the public/unauth requirement on assetlinks) requires a
coordinated change in both `mdopp/solaris-android` and `mdopp/solarisbay`.
