package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
     * PWA deep-link routes appended to `ServerStore.baseUrl`. Confirmed server-side
     * in solarisbay#769 (the PWA is hash-routed under `/#/p/…`); centralised here so
     * every widget tap lands on the exact view.
     */
    object Routes {
        /** Energy widgets → the energy portal view (confirmed solarisbay#769). */
        const val ENERGY = "/#/p/energy"

        /** Generic/overview tap → the PWA root. */
        const val ROOT = "/"

        /**
         * ServiceBay widgets (#42/#43/#44/#45) tap → the ServiceBay view in the
         * PWA. Exact deep-link still to be confirmed server-side; opens the PWA
         * root for now so the tap always lands somewhere sensible.
         */
        const val SERVICEBAY = "/"

        /**
         * A single device's card view (confirmed solarisbay#769) — a tap on a
         * device widget's name, or an active-devices row, opens exactly that
         * entity's card.
         */
        fun device(entityId: String): String = "/#/p/device/$entityId"

        /**
         * Camera widget (#36): open the PWA's live camera view for [entityId]
         * (confirmed placeholder, solarisbay#782). The camera widget is a pure
         * trigger tile — it no longer loads a snapshot; a tap lands on the live
         * view. Falls back to the generic camera root when no entity is bound.
         */
        fun camera(entityId: String): String = "/#/p/camera/$entityId"

        /** The generic camera view when no specific entity is bound yet (#36). */
        const val CAMERA_ROOT = "/#/p/camera"

        /**
         * A single ServiceBay approval's **verdict page** (#43, solarisbay#1085).
         * [id] is the approval id from the `servicebay` SSE frame `{id,kind,summary}`
         * or `GET /napi/servicebay/approvals`. The page's buttons POST the verdict
         * over the Authelia session — the device token cannot (servicebay#2249),
         * which is why a tap must land here in the Custom Tab, not render natively.
         *
         * The **hash is not cosmetic**: server-side `/p/{type}` matches only one path
         * segment, so `/p/servicebay/approvals/<id>` 404s — only the hash form, which
         * the client router resolves, works. `PwaLauncherUrlTest` locks this shape.
         */
        fun approval(id: String): String = "/#/p/servicebay/approvals/$id"

        /**
         * Voice widget (#17): open a NEW household chat pre-loaded with [text] as the
         * user turn, which the PWA auto-sends once on load (confirmed solarisbay#766,
         * consumed via history.replaceState). The text is URL-encoded so spaces and
         * umlauts survive. Pure/Android-free so it's unit-testable.
         */
        fun ask(text: String): String =
            "/#/?ask=" + java.net.URLEncoder.encode(text, "UTF-8")
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

    /**
     * The `PendingIntentTemplate` for a collection widget's ListView (#28): a single
     * mutable [PendingIntent] into [PwaTrampolineActivity] that each row's fill-in
     * intent completes (supplying the target PWA path). Must be **mutable** so the
     * collection can merge the per-row fill-in; `reqCode` is unique per widget id.
     */
    fun rowTapTemplate(ctx: Context, reqCode: Int): PendingIntent {
        val i = Intent(ctx, PwaTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mutable =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(
            ctx, 300_000 + reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }
}
