package cloud.dopp.solaris.data

import android.content.Context
import android.net.Uri

/**
 * The Solaris server this app is paired to. Multi-tenant: the URL is chosen by
 * the user at onboarding (QR/entry), not hardcoded. Plain prefs — the URL is not
 * a secret (the device token in [TokenStore] is).
 */
object ServerStore {
    private const val PREFS = "solaris_server"
    private const val KEY = "base_url"
    private const val KEY_PWA_HINT_DISMISSED = "pwa_hint_dismissed"
    private const val KEY_REALTIME = "realtime_enabled"
    private const val KEY_POLL_SEC = "realtime_poll_seconds"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** e.g. `https://chat.dopp.cloud` (no trailing slash), or null if unset. */
    fun baseUrl(ctx: Context): String? = p(ctx).getString(KEY, null)

    fun setBaseUrl(ctx: Context, url: String) {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            u = "https://$u"
        }
        p(ctx).edit().putString(KEY, u).apply()
    }

    fun isConfigured(ctx: Context): Boolean = !baseUrl(ctx).isNullOrBlank()

    fun host(ctx: Context): String? =
        baseUrl(ctx)?.let { runCatching { Uri.parse(it).host }.getOrNull() }

    /**
     * The "Solaris-App installieren" onboarding hint (#11): the app can't reliably
     * detect an installed WebAPK, so the user dismisses the card manually and we
     * remember it so future launches don't show it again.
     */
    fun isPwaHintDismissed(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_PWA_HINT_DISMISSED, false)

    fun dismissPwaHint(ctx: Context) =
        p(ctx).edit().putBoolean(KEY_PWA_HINT_DISMISSED, true).apply()

    /**
     * Live-Updates opt-in (#48): keep an embedded SSE foreground service running
     * so device-widgets wake instantly on `card_state` pushes. **Default false** —
     * it costs a persistent notification and a little battery, so the user turns it
     * on deliberately.
     */
    fun realtimeEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_REALTIME, false)

    fun setRealtimeEnabled(ctx: Context, enabled: Boolean) =
        p(ctx).edit().putBoolean(KEY_REALTIME, enabled).apply()

    /**
     * Screen-off responsiveness in **seconds** (#48). Default 240 (4 min). 0 = off
     * (screen-on live only, nothing while asleep). < 60 = keep the live SSE open even
     * when the screen is off (real second-level updates, more battery); ≥ 60 = drop
     * the SSE and run a backstop poll at this interval (battery saving). Clamped.
     */
    fun pollSeconds(ctx: Context): Int = p(ctx).getInt(KEY_POLL_SEC, 240)

    fun setPollSeconds(ctx: Context, seconds: Int) =
        p(ctx).edit().putInt(KEY_POLL_SEC, seconds.coerceIn(0, 24 * 60 * 60)).apply()

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()
}
