package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity

/**
 * Opens the Solaris **PWA** in a Custom Tab from a widget tap (#27) — so tapping a
 * widget lands in the live web view (energy screen / device view), not the bare
 * app shell. Mirrors the onboarding `openUrl` pattern: Custom Tab first, a plain
 * VIEW intent as fallback (both covered by the manifest `<queries>`), and the
 * onboarding hub if the server isn't configured yet.
 */
object PwaLauncher {

    /**
     * PWA deep-link routes appended to `ServerStore.baseUrl`. The **exact** routes
     * (hash vs path, device-specific views) are being defined server-side in
     * solarisbay#769 — until then these are best-effort and centralised here so a
     * single edit refines them once #769 lands.
     */
    object Routes {
        /** Energy widgets → the energy view. Best-effort until solarisbay#769. */
        const val ENERGY = "/#/energy"

        /** Device widget → root (device-specific route pending solarisbay#769). */
        const val ROOT = "/"
    }

    /**
     * Open `baseUrl + path` in a Custom Tab (VIEW-intent fallback). If no server is
     * configured, bounce to the onboarding hub so the user can pair first.
     */
    fun open(ctx: Context, path: String) {
        val base = ServerStore.baseUrl(ctx)
        if (base.isNullOrBlank()) {
            openHome(ctx)
            return
        }
        val uri = Uri.parse(url(base, path))
        val app = ctx.applicationContext
        try {
            val ct = CustomTabsIntent.Builder().build()
            ct.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ct.launchUrl(app, uri)
        } catch (e: Exception) {
            try {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Exception) {
                openHome(app)
            }
        }
    }

    /**
     * Join [base] + [path] into a single URL, collapsing the seam so a trailing
     * slash on the base and a leading slash on the path never double up. Pure and
     * Android-free so it's unit-testable (#27).
     */
    fun url(base: String, path: String): String = base.trimEnd('/') + path

    private fun openHome(ctx: Context) {
        ctx.applicationContext.startActivity(
            Intent(ctx.applicationContext, OnboardingHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * A widget body-tap [PendingIntent] that opens the PWA at [path] via the
     * invisible [PwaTrampolineActivity]. `reqCode` must be unique per widget id so
     * distinct instances don't share (and overwrite) each other's intent.
     */
    fun tapPending(ctx: Context, reqCode: Int, path: String): PendingIntent {
        val i = Intent(ctx, PwaTrampolineActivity::class.java)
            .putExtra(PwaTrampolineActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
