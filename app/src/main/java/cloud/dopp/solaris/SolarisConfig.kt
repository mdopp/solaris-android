package cloud.dopp.solaris

/**
 * Shared configuration for the Phase-3 native surfaces (home-screen widgets,
 * quick-settings tile, device-token pairing).
 *
 * The TWA shell itself targets [BASE_URL]; the native code here talks to the
 * same origin but authenticates with a `sol_device_…` bearer token instead of
 * the browser's Authelia cookies (see docs/CONTRACT.md).
 */
object SolarisConfig {
    /** Origin of the Solaris PWA / API. */
    const val BASE_URL = "https://chat.dopp.cloud"

    /** Android application id — must match twa-manifest.json `packageId`. */
    const val PACKAGE = "cloud.dopp.solaris"

    /**
     * Deep link the device-token pairing redirect lands on:
     * `cloud.dopp.solaris://pair?token=sol_device_…`. The pairing web page
     * (served by solarisbay) mints the token from the authenticated Authelia
     * session and redirects here; the native app captures and stores it.
     */
    const val PAIR_SCHEME = "cloud.dopp.solaris"
    const val PAIR_HOST = "pair"
    const val PAIR_TOKEN_PARAM = "token"

    /** Authenticated pairing page opened in a Chrome Custom Tab. */
    const val PAIR_URL = "$BASE_URL/pair-device"
}
