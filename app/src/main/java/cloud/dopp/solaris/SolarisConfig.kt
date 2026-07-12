package cloud.dopp.solaris

/**
 * Static app identity + native-API constants. The **server URL is NOT here** —
 * it is per-install and lives in [cloud.dopp.solaris.data.ServerStore] (the app
 * is multi-tenant: each user pairs it to their own Solaris server).
 */
object SolarisConfig {
    /** Android application id — the pairing deep-link scheme equals this. */
    const val PACKAGE = "cloud.dopp.solaris"

    /**
     * Proxy-bypassed, device-token-only native API prefix (solarisbay#757).
     * All widget/pairing calls go to `<serverUrl>$NAPI/…`.
     */
    const val NAPI = "/napi"

    /** Path of the authenticated pairing page on the user's server. */
    const val PAIR_PATH = "/pair-device"

    /**
     * Pairing redirect deep link: `cloud.dopp.solaris://pair#token=…&id=…`
     * (token in the fragment so it never hits proxy logs).
     */
    const val PAIR_SCHEME = "cloud.dopp.solaris"
    const val PAIR_HOST = "pair"
    const val PAIR_TOKEN_PARAM = "token"

    /** Prefilled hint in the onboarding server field. */
    const val DEFAULT_SERVER_HINT = "https://chat.dopp.cloud"
}
