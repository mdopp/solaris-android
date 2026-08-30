package cloud.dopp.solaris.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import androidx.core.content.ContextCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.widget.PwaLauncher

/**
 * The Solaris web surface **inside the app** (#115) — one icon instead of two.
 *
 * Until now the app opened `chat.dopp.cloud` in a plain Custom Tab, next to a
 * separately installed PWA. The Digital-Asset-Links handshake this repo was
 * built around is still live server-side (`ANDROID_CERT_FINGERPRINTS` carries
 * our signing fingerprint, `/.well-known/assetlinks.json` names
 * `cloud.dopp.solaris`), so the same page can be shown as a **Trusted Web
 * Activity**: full screen, no URL bar, same Chrome cookie jar — therefore the
 * same Authelia session and no second login.
 *
 * This object is the launch mechanics only; [SolarisSurfaceActivity] is the
 * screen that hosts it, and every existing caller reaches it through
 * [PwaLauncher] so widgets, shortcuts and notifications land in the same view.
 *
 * **Deliberately not an external entry point.** No `intent-filter` for
 * `https://chat.dopp.cloud` is declared anywhere — an exported, verified filter
 * would let *any* app on the phone push a URL into the chromeless, signed-in
 * household surface. The surface is opened by the app itself, and by nothing
 * else.
 *
 * **Only our own origin is trusted.** A ServiceBay/admin URL (#45/#109) lives on
 * another host that our assetlinks file does not cover; shown as a TWA it would
 * fail verification and drop back to a toolbar anyway. [isOwnOrigin] keeps those
 * on the Custom Tab path, which is also the fallback whenever no
 * TWA-capable browser is installed (`twaManifest.fallbackType: 'customtabs'`).
 */
object SolarisSurface {

    /** Category a Custom-Tabs provider declares when it can host a TWA. */
    const val TWA_CATEGORY = "androidx.browser.trusted.category.TrustedWebActivities"

    /** Absolute URL to show; set by [PwaLauncher] on the surface intent. */
    const val EXTRA_URL = "surface_url"

    /** Base-relative route ([PwaLauncher.Routes]) when no absolute URL is given. */
    const val EXTRA_PATH = "surface_path"

    /**
     * The URL the surface should show, or `null` when there is nothing to show
     * yet — an unpaired install has no base, and the caller then belongs in
     * onboarding rather than on an error page. Pure/Android-free so it's
     * unit-testable.
     */
    fun target(base: String?, path: String?, absolute: String?): String? {
        if (!absolute.isNullOrBlank()) return absolute
        if (base.isNullOrBlank()) return null
        return PwaLauncher.url(base, path?.takeIf { it.isNotBlank() } ?: PwaLauncher.Routes.ROOT)
    }

    /**
     * Whether [url] is on the paired server's own origin — scheme, host and port,
     * the three things Digital Asset Links is scoped to. Anything else (the
     * ServiceBay admin host, an external link) is not ours to show chromeless.
     * Pure/Android-free so it's unit-testable.
     */
    fun isOwnOrigin(base: String?, url: String?): Boolean {
        if (base.isNullOrBlank() || url.isNullOrBlank()) return false
        val a = origin(base) ?: return false
        return a == origin(url)
    }

    /**
     * `scheme://host[:port]` of [u], or null when it doesn't parse. The fragment
     * is cut off first: the PWA is hash-routed (`/#/p/…`) and a hash payload may
     * carry characters `java.net.URI` refuses.
     */
    private fun origin(u: String): String? = runCatching {
        val cut = u.indexOf('#')
        val head = if (cut >= 0) u.substring(0, cut) else u
        val uri = java.net.URI(head.trim())
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        "$scheme://$host$port"
    }.getOrNull()

    /**
     * Start the in-app surface for [url]. Returns false when the activity can't
     * be started at all, so the caller can keep the old Custom Tab behaviour
     * instead of dropping the tap.
     */
    fun start(ctx: Context, url: String): Boolean {
        val app = ctx.applicationContext
        val i = Intent(app, SolarisSurfaceActivity::class.java)
            .putExtra(EXTRA_URL, url)
            // Same affinity as the app's task, so a widget tap lands in the
            // Solaris task (and in Recents as Solaris) rather than in a throwaway.
            // CLEAR_TOP + singleTop: a second tap re-navigates the open surface
            // instead of stacking a new one behind the browser.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    /** True when [filter] belongs to a Custom-Tabs service that can host a TWA. */
    fun handlesTwa(filter: IntentFilter?): Boolean = filter?.hasCategory(TWA_CATEGORY) == true

    /**
     * A browser package that can host a Trusted Web Activity — the user's default
     * Custom-Tabs provider first, then any other installed one. Null means no TWA
     * anywhere on this phone, and the Custom Tab stays the surface.
     */
    fun twaProvider(ctx: Context): String? {
        val pm = ctx.packageManager ?: return null
        val candidates = LinkedHashSet<String>()
        runCatching { CustomTabsClient.getPackageName(ctx, null) }.getOrNull()?.let { candidates += it }
        runCatching {
            pm.queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
        }.getOrNull()?.forEach { it.serviceInfo?.packageName?.let(candidates::add) }
        return candidates.firstOrNull { supportsTwa(pm, it) }
    }

    private fun supportsTwa(pm: PackageManager, pkg: String): Boolean {
        val i = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(pkg)
        val res = runCatching {
            pm.resolveService(i, PackageManager.GET_RESOLVED_FILTER)
        }.getOrNull()
        return handlesTwa(res?.filter)
    }

    /**
     * Bind [pkg]'s Custom-Tabs service and launch [url] as a Trusted Web Activity
     * from [activity]. [onResult] reports whether the TWA actually started, so the
     * host can fall back to a Custom Tab.
     *
     * The returned connection must be unbound by the caller — and only when the
     * surface is finished: Chrome verifies the Digital-Asset-Links relationship
     * through this session, so dropping it early is what puts the URL bar back.
     */
    fun bindAndLaunch(
        activity: Activity,
        pkg: String,
        url: String,
        onResult: (Boolean) -> Unit,
    ): CustomTabsServiceConnection? {
        val conn = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                onResult(runCatching { launchTwa(activity, client, url) }.getOrDefault(false))
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = runCatching {
            CustomTabsClient.bindCustomTabsService(activity, pkg, conn)
        }.getOrDefault(false)
        return if (bound) conn else null
    }

    private fun launchTwa(activity: Activity, client: CustomTabsClient, url: String): Boolean {
        client.warmup(0)
        val session = client.newSession(null) ?: return false
        val uri = Uri.parse(url)
        // Asks the browser to verify /.well-known/assetlinks.json for this origin.
        // Verification is what removes the URL bar; it fails soft (toolbar shown).
        origin(url)?.let {
            session.validateRelationship(CustomTabsService.RELATION_HANDLE_ALL_URLS, Uri.parse(it), null)
        }
        TrustedWebActivityIntentBuilder(uri)
            .setDefaultColorSchemeParams(colors(activity))
            .build(session)
            .launchTrustedWebActivity(activity)
        return true
    }

    /**
     * Status/navigation bar colours for the surface — the `twaManifest` theme
     * values (`themeColor`, `navigationColor`, `navigationDividerColor`), which
     * have sat in `app/build.gradle` as unused `resValue`s since the TWA was
     * retired and are back in service here.
     */
    private fun colors(ctx: Context): CustomTabColorSchemeParams =
        CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(ctx, R.color.colorPrimary))
            .setNavigationBarColor(ContextCompat.getColor(ctx, R.color.navigationColor))
            .setNavigationBarDividerColor(ContextCompat.getColor(ctx, R.color.navigationDividerColor))
            .build()
}
