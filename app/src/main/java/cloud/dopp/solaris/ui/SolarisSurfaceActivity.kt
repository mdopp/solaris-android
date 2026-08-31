package cloud.dopp.solaris.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsServiceConnection
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.widget.PwaLauncher

/**
 * The screen that **is** the Solaris surface (#115): it hosts the Trusted Web
 * Activity showing the user's own server, so a tap on a widget, a shortcut, a
 * notification — or on "Zu Solaris" on the hub — lands in the chat instead of in
 * a Custom Tab beside a separately installed PWA. The **app icon** goes to the
 * hub, not here (#129): the surface has a way out, the hub needed a way in.
 *
 * It draws nothing itself (the window background is the splash), because a TWA
 * is rendered by the browser inside *this* task. The activity stays alive
 * underneath for exactly one reason: the browser verifies our
 * `/.well-known/assetlinks.json` relationship through the Custom-Tabs session
 * held here, and letting that go early is what brings the URL bar back.
 *
 * Three behaviours are load-bearing and are what the device test looks at:
 *  - **Unpaired leads to onboarding**, never to an error page — with no server
 *    there is no surface, so the tap goes to [OnboardingHomeActivity].
 *  - **Back is not a dead end.** The browser walks the web history first; when it
 *    is exhausted the browser finishes, this host comes back, and it finishes
 *    itself — so the user returns to the hub that opened it (or, for a widget tap
 *    into an app task that did not exist yet, leaves the app) instead of landing
 *    on a blank screen.
 *  - **Fallback stays the Custom Tab** (`twaManifest.fallbackType: 'customtabs'`)
 *    when no installed browser can host a TWA, when the binding fails, or when it
 *    simply doesn't answer.
 *
 * Not exported, and carrying **no** `intent-filter` for `chat.dopp.cloud`: the
 * app opens its own surface, and no other app on the phone gets a way to push a
 * URL into the signed-in household view.
 */
class SolarisSurfaceActivity : Activity() {

    private var conn: CustomTabsServiceConnection? = null
    private var url: String? = null

    /** The surface (TWA or Custom Tab) has been put on screen. */
    private var launched = false

    /** We have been backgrounded at least once — i.e. the surface really showed. */
    private var wasStopped = false

    private val handler = Handler(Looper.getMainLooper())
    private val bindTimeout = Runnable { if (!launched) fallback() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second tap while the surface is open re-navigates it (the browser
        // activity above us was cleared by FLAG_ACTIVITY_CLEAR_TOP).
        release()
        launched = false
        wasStopped = false
        open(intent)
    }

    private fun open(intent: Intent?) {
        val base = ServerStore.baseUrl(this)
        val target = SolarisSurface.target(
            base,
            intent?.getStringExtra(SolarisSurface.EXTRA_PATH),
            intent?.getStringExtra(SolarisSurface.EXTRA_URL),
        )
        if (target == null) {
            openOnboarding()
            return
        }
        url = target
        // Only our own origin is covered by the assetlinks handshake; anything
        // else would fail verification and show a toolbar anyway.
        if (!SolarisSurface.isOwnOrigin(base, target)) {
            fallback()
            return
        }
        val pkg = SolarisSurface.twaProvider(this)
        if (pkg == null) {
            fallback()
            return
        }
        conn = SolarisSurface.bindAndLaunch(this, pkg, target) { ok ->
            handler.removeCallbacks(bindTimeout)
            if (ok) launched = true else fallback()
        }
        if (conn == null) fallback() else handler.postDelayed(bindTimeout, BIND_TIMEOUT_MS)
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onResume() {
        super.onResume()
        // Coming back after the surface was shown means the user backed out of it
        // (web history exhausted) — don't park them on the empty host.
        if (launched && wasStopped) finish()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        handler.removeCallbacks(bindTimeout)
        conn?.let { c -> runCatching { unbindService(c) } }
        conn = null
    }

    /** No TWA available (or it never answered) — keep the old Custom Tab path. */
    private fun fallback() {
        val u = url
        if (u.isNullOrBlank()) {
            openOnboarding()
            return
        }
        release()
        PwaLauncher.customTab(this, u)
        launched = true
    }

    private fun openOnboarding() {
        runCatching {
            startActivity(
                Intent(this, OnboardingHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    companion object {
        /** How long to wait for the browser's Custom-Tabs service before falling back. */
        private const val BIND_TIMEOUT_MS = 2_500L
    }
}
