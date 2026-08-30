package cloud.dopp.solaris

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.ui.SolarisSurface
import cloud.dopp.solaris.ui.SolarisSurfaceActivity
import cloud.dopp.solaris.widget.PwaLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Solaris surface inside the app (#115) — one icon instead of two.
 *
 * What is pinned here is everything the JVM can see of it; whether the browser
 * really drops the URL bar is the device test's half.
 *
 * 1. **Where a tap goes.** The paired server's own origin opens in
 *    [SolarisSurfaceActivity]; every other host — the ServiceBay admin surface
 *    above all (#45/#109) — keeps the Custom Tab, because our assetlinks
 *    handshake covers only the one origin.
 * 2. **The unpaired state leads to onboarding**, from both directions: a widget
 *    tap without a server, and the surface activity started without one. Never an
 *    error page.
 * 3. **The app icon opens the surface** when the install is paired, and stays on
 *    the onboarding hub when it is not.
 * 4. **The fallback still works** — with no TWA-capable browser installed (which
 *    is the case here on the JVM), the surface falls back to the Custom Tab
 *    instead of leaving the tap on a blank screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurfaceLaunchTest {

    private val base = "https://chat.dopp.cloud"

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** [TokenStore] memoises across tests — start every one genuinely unpaired. */
    @Before
    fun unpair() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    private fun paired(withToken: Boolean = true) {
        ServerStore.setBaseUrl(ctx(), base)
        if (withToken) TokenStore.save(ctx(), "sol_device_TESTTOKEN123")
    }

    /**
     * Every activity intent started so far, oldest first. Drained once and kept —
     * `nextStartedActivity` pops, so a second reader would find nothing.
     */
    private val startedIntents = mutableListOf<Intent>()

    private fun started(): List<Intent> {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        while (true) startedIntents += (shadow.nextStartedActivity ?: break)
        return startedIntents
    }

    private fun startedOn(cls: Class<*>): Intent? =
        started().firstOrNull { it.component?.className == cls.name }

    // --- 1. the URL the surface should show -----------------------------------

    @Test
    fun unpairedHasNoTarget_soTheCallerCanGoToOnboarding() {
        assertNull(SolarisSurface.target(null, PwaLauncher.Routes.ROOT, null))
        assertNull(SolarisSurface.target("", PwaLauncher.Routes.ENERGY, null))
    }

    @Test
    fun aRouteIsJoinedOntoThePairedBase() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/energy",
            SolarisSurface.target(base, PwaLauncher.Routes.ENERGY, null),
        )
    }

    @Test
    fun noRouteMeansTheRoot() {
        assertEquals("https://chat.dopp.cloud/", SolarisSurface.target(base, null, null))
        assertEquals("https://chat.dopp.cloud/", SolarisSurface.target(base, "", null))
    }

    @Test
    fun anAbsoluteUrlWinsOverARoute() {
        assertEquals(
            "https://admin.dopp.cloud/",
            SolarisSurface.target(base, PwaLauncher.Routes.ENERGY, "https://admin.dopp.cloud/"),
        )
    }

    // --- 2. which origin may be shown chromeless ------------------------------

    @Test
    fun thePairedOriginIsOurs_hashRouteAndTrailingSlashIncluded() {
        assertTrue(SolarisSurface.isOwnOrigin(base, "$base/#/p/device/light.kitchen"))
        assertTrue(SolarisSurface.isOwnOrigin("$base/", "$base/"))
        assertTrue(SolarisSurface.isOwnOrigin(base, "https://CHAT.dopp.cloud/#/p/start"))
    }

    /** The ServiceBay admin host (#45/#109) is a different origin — Custom Tab. */
    @Test
    fun theAdminHostIsNotOurs() {
        assertFalse(SolarisSurface.isOwnOrigin(base, "https://admin.dopp.cloud/services"))
    }

    @Test
    fun schemeAndPortArePartOfTheOrigin() {
        assertFalse(SolarisSurface.isOwnOrigin(base, "http://chat.dopp.cloud/"))
        assertFalse(SolarisSurface.isOwnOrigin(base, "https://chat.dopp.cloud:8443/"))
        assertTrue(
            SolarisSurface.isOwnOrigin("https://sol.local:8443", "https://sol.local:8443/#/p/start"),
        )
    }

    @Test
    fun nothingIsOursWithoutAPairedBase() {
        assertFalse(SolarisSurface.isOwnOrigin(null, "$base/"))
        assertFalse(SolarisSurface.isOwnOrigin(base, null))
        assertFalse(SolarisSurface.isOwnOrigin(base, "not a url"))
    }

    /** A provider can host a TWA only when its service declares the category. */
    @Test
    fun onlyTheTwaCategoryCountsAsATwaProvider() {
        assertTrue(SolarisSurface.handlesTwa(IntentFilter().apply { addCategory(SolarisSurface.TWA_CATEGORY) }))
        assertFalse(SolarisSurface.handlesTwa(IntentFilter().apply { addCategory(Intent.CATEGORY_BROWSABLE) }))
        assertFalse(SolarisSurface.handlesTwa(null))
    }

    /** No browser is installed on the JVM — so the Custom Tab stays the surface. */
    @Test
    fun noTwaCapableBrowser_meansNoProvider() {
        assertNull(SolarisSurface.twaProvider(ctx()))
    }

    // --- 3. where the existing entry points land ------------------------------

    @Test
    fun aWidgetTapOnTheOwnOriginOpensTheInAppSurface() {
        paired()
        PwaLauncher.open(ctx(), PwaLauncher.Routes.ENERGY)
        val i = startedOn(SolarisSurfaceActivity::class.java)
        assertNotNull("widget tap must land in the in-app surface", i)
        assertEquals("$base/#/p/energy", i!!.getStringExtra(SolarisSurface.EXTRA_URL))
    }

    /** #45/#109: the admin surface is another host — it must NOT be shown chromeless. */
    @Test
    fun theServiceBayTapStaysInACustomTab() {
        paired()
        PwaLauncher.openAbsolute(ctx(), "https://admin.dopp.cloud/")
        assertNull(startedOn(SolarisSurfaceActivity::class.java))
        assertTrue(started().any { it.data?.toString() == "https://admin.dopp.cloud/" })
    }

    @Test
    fun aWidgetTapWithoutAServerGoesToOnboarding() {
        PwaLauncher.open(ctx(), PwaLauncher.Routes.ENERGY)
        assertNotNull(startedOn(OnboardingHomeActivity::class.java))
        assertNull(startedOn(SolarisSurfaceActivity::class.java))
    }

    // --- 4. the surface activity itself ---------------------------------------

    @Test
    fun theSurfaceWithoutAServerLeadsToOnboarding_notAnErrorPage() {
        val activity = Robolectric.buildActivity(SolarisSurfaceActivity::class.java).setup().get()
        assertNotNull(startedOn(OnboardingHomeActivity::class.java))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun theSurfaceFallsBackToTheCustomTabWhenNoBrowserCanHostIt() {
        paired()
        val intent = Intent(ctx(), SolarisSurfaceActivity::class.java)
            .putExtra(SolarisSurface.EXTRA_PATH, PwaLauncher.Routes.ENERGY)
        Robolectric.buildActivity(SolarisSurfaceActivity::class.java, intent).setup()
        assertTrue(
            "the tap must still reach the PWA",
            started().any { it.data?.toString() == "$base/#/p/energy" },
        )
    }

    // --- 5. the app icon -------------------------------------------------------

    @Test
    fun theAppIconOpensTheSurfaceWhenPaired() {
        paired()
        val main = Intent(Intent.ACTION_MAIN).setClass(ctx(), OnboardingHomeActivity::class.java)
        Robolectric.buildActivity(OnboardingHomeActivity::class.java, main).setup()
        assertNotNull(startedOn(SolarisSurfaceActivity::class.java))
    }

    /** A half-paired install (server set, no device token) still needs onboarding. */
    @Test
    fun theAppIconStaysOnTheHubWhenNotPaired() {
        paired(withToken = false)
        val main = Intent(Intent.ACTION_MAIN).setClass(ctx(), OnboardingHomeActivity::class.java)
        Robolectric.buildActivity(OnboardingHomeActivity::class.java, main).setup()
        assertNull(startedOn(SolarisSurfaceActivity::class.java))
    }

    /** The surface's own bounce back to onboarding must not bounce forward again. */
    @Test
    fun anInternallyStartedHubDoesNotReopenTheSurface() {
        paired()
        Robolectric.buildActivity(OnboardingHomeActivity::class.java).setup()
        assertNull(startedOn(SolarisSurfaceActivity::class.java))
    }
}
