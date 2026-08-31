package cloud.dopp.solaris

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.ui.SolarisSurface
import cloud.dopp.solaris.ui.SolarisSurfaceActivity
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowDialog

/**
 * The home screen after the polish pass (#126/#127) — device feedback from the
 * operator: the first screen needed scrolling, the status line was not flush,
 * the Live-Updates text explained too much, and the way into Solaris promised a
 * jump out of the app that no longer happens.
 *
 * What this pins is the part a JVM can see: **priority and reachability**, not
 * pixels. Whether it now fits one display is the device test's half — but every
 * claim underneath it is checked here:
 *
 *  1. the paired hub keeps the brand block, in its compact presentation (#130),
 *     and the status line right underneath it,
 *  2. the extra widget groups start folded and are one tap from being back —
 *     "Weitere Widgets" hides them, it never removes them,
 *  3. the way into Solaris is the first action and starts the in-app surface,
 *  4. the Live-Updates card says one short line; the explanation is in a dialog,
 *  5. the unpaired state keeps every onboarding path, and the diagnostics entry
 *     on the version line (#110) still opens,
 *  6. the shell and the surface paint their system bars from the same tokens,
 *  7. the content starts below the status bar (#130) — the screen is edge-to-edge
 *     under targetSdk 35 and has to clear the insets itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    private val base = "https://chat.dopp.cloud"

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    /** [TokenStore] memoises across tests — start every one genuinely unpaired. */
    @Before
    fun unpair() {
        ServerStore.clear(ctx())
        TokenStore.clear(ctx())
    }

    private fun paired() {
        ServerStore.setBaseUrl(ctx(), base)
        TokenStore.save(ctx(), "sol_device_TESTTOKEN123")
    }

    /**
     * The hub — since #129 this is also what the app icon opens, paired or not, so
     * a plain start and an `ACTION_MAIN` start reach the same screen.
     */
    private fun hub(): OnboardingHomeActivity =
        Robolectric.buildActivity(OnboardingHomeActivity::class.java).setup().get()

    private fun startedOn(cls: Class<*>): Intent? {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        val all = generateSequence { shadow.nextStartedActivity }.toList()
        return all.firstOrNull { it.component?.className == cls.name }
    }

    private fun visible(v: View?) = v != null && v.visibility == View.VISIBLE

    // --- 1. what the paired screen leads with ---------------------------------

    /**
     * #126 hid the wordmark once paired; the operator asked for it back (#130).
     * It is back in both states — smaller when there is content under it, and
     * without the first-run claim, so #126's prioritisation survives.
     */
    @Test
    fun thePairedHubKeepsTheWordmarkButPresentsItSmaller() {
        paired()
        val a = hub()
        assertTrue(visible(a.findViewById(R.id.connected_section)))
        assertTrue(
            "the wordmark in its glow is the screen's header in both states",
            visible(a.findViewById(R.id.brand_block)),
        )
        assertTrue(
            "the first-run claim has nothing to say to a paired install",
            !visible(a.findViewById(R.id.brand_claim)),
        )
        val glow = a.findViewById<View>(R.id.brand_glow).layoutParams.height
        assertEquals(a.resources.getDimensionPixelSize(R.dimen.hero_glow_size_compact), glow)
        assertTrue(
            "the paired lockup must be smaller than the first-run one",
            glow < a.resources.getDimensionPixelSize(R.dimen.hero_glow_size),
        )
        assertEquals(
            "Verbunden mit chat.dopp.cloud",
            a.findViewById<TextView>(R.id.status).text.toString(),
        )
    }

    /** #126: the pill sat at wrap_content while everything around it was flush. */
    @Test
    fun theStatusLineFillsTheColumnLikeTheCards() {
        paired()
        val row = hub().findViewById<View>(R.id.status).parent as View
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, row.layoutParams.width)
    }

    // --- 2. prioritisation, not removal ---------------------------------------

    @Test
    fun theExtraWidgetGroupsStartFoldedAway() {
        paired()
        val a = hub()
        assertTrue(!visible(a.findViewById(R.id.more_widgets)))
        // The five household types are what stays on the first screen.
        listOf(R.id.tile_device, R.id.tile_energy, R.id.tile_active, R.id.tile_camera, R.id.tile_voice)
            .forEach { assertTrue(visible(a.findViewById(it))) }
        assertEquals(
            a.getString(R.string.home_more_widgets),
            a.findViewById<TextView>(R.id.more_widgets_toggle).text.toString(),
        )
    }

    @Test
    fun oneTapBringsTheServiceBayAndToolWidgetsBack() {
        paired()
        val a = hub()
        a.findViewById<View>(R.id.more_widgets_toggle).performClick()
        assertTrue(visible(a.findViewById(R.id.more_widgets)))
        assertTrue(visible(a.findViewById(R.id.tile_sb_overview)))
        assertTrue(visible(a.findViewById(R.id.tile_sb_services)))
        // The tools group is catalog-driven (#72) and stays hidden while empty,
        // but it is inside the section that just opened.
        assertNotNull(a.findViewById<View>(R.id.tools_group))
        assertEquals(
            a.getString(R.string.home_less_widgets),
            a.findViewById<TextView>(R.id.more_widgets_toggle).text.toString(),
        )
        // …and folds away again.
        a.findViewById<View>(R.id.more_widgets_toggle).performClick()
        assertTrue(!visible(a.findViewById(R.id.more_widgets)))
    }

    // --- 3. the way into Solaris (#127) ---------------------------------------

    @Test
    fun theWayIntoSolarisIsTheFirstActionAndOpensTheInAppSurface() {
        paired()
        val a = hub()
        val section = a.findViewById<LinearLayout>(R.id.connected_section)
        val open = section.indexOfChild(a.findViewById(R.id.open_btn))
        val widgets = section.indexOfChild(a.findViewById(R.id.widgets_teaser_card))
        assertTrue("the product's main surface must not sit below the settings", open in 0 until widgets)

        a.findViewById<View>(R.id.open_btn).performClick()
        assertNotNull("the button switches the view inside the app", startedOn(SolarisSurfaceActivity::class.java))
    }

    /** The label may not promise a jump out of the app that no longer happens. */
    @Test
    fun theLabelDoesNotPromiseAnExternalJump() {
        val label = ctx().getString(R.string.home_open)
        assertTrue(label.isNotBlank())
        assertTrue("'öffnen' described the retired Custom Tab", !label.contains("öffnen"))
    }

    // --- 4. short on the card, complete in the dialog (#126) -------------------

    @Test
    fun theLiveUpdatesCardSaysOneShortLine() {
        val hint = ctx().getString(R.string.home_realtime_hint)
        assertTrue("the card line has to fit next to the switch: '$hint'", hint.length <= 60)
    }

    @Test
    fun theExplanationMovedBehindTheInfoIconRatherThanAway() {
        paired()
        val a = hub()
        a.findViewById<View>(R.id.realtime_info).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val text = a.getString(R.string.home_realtime_info)
        listOf("Benachrichtigung", "Akku", "Ruhemodus").forEach {
            assertTrue("the $it explanation must survive the move", text.contains(it))
        }
    }

    // --- 5. nothing lost ------------------------------------------------------

    @Test
    fun theUnpairedScreenKeepsEveryOnboardingPath() {
        val a = hub()
        assertTrue(visible(a.findViewById(R.id.brand_block)))
        assertTrue("the first run is the full presentation", visible(a.findViewById(R.id.brand_claim)))
        assertEquals(
            a.resources.getDimensionPixelSize(R.dimen.hero_glow_size),
            a.findViewById<View>(R.id.brand_glow).layoutParams.height,
        )
        assertTrue(visible(a.findViewById(R.id.connect_section)))
        assertTrue(!visible(a.findViewById(R.id.connected_section)))
        listOf(R.id.qr_scan_btn, R.id.server_url, R.id.connect_btn, R.id.request_access_btn)
            .forEach { assertTrue(visible(a.findViewById(it))) }
    }

    /** #110: the version line is the only way to reach the diagnostics screen. */
    @Test
    fun theVersionLineStillOpensDiagnostics() {
        paired()
        val a = hub()
        a.findViewById<View>(R.id.app_version).performClick()
        assertNotNull(startedOn(cloud.dopp.solaris.ui.DiagnosticsActivity::class.java))
    }

    @Test
    fun signOutStaysReachable() {
        paired()
        val a = hub()
        val out = a.findViewById<View>(R.id.logout_btn)
        assertTrue(visible(out))
        out.performClick()
        assertTrue("sign out must actually unpair", !TokenStore.isPaired(ctx()))
        assertNull(ServerStore.baseUrl(ctx()))
    }

    // --- 6. the frame does not change colour at the seam (#127) ---------------

    @Test
    fun theShellAndTheSurfacePaintTheirBarsFromTheSameTokens() {
        val theme = ctx().getColor(R.color.solaris_surface_theme)
        val bg = ctx().getColor(R.color.solaris_surface_bg)
        val params = SolarisSurface.colors(ctx())
        assertEquals(theme, params.toolbarColor ?: 0)
        assertEquals(bg, params.navigationBarColor ?: 0)
        // A 1px divider in a third colour is a seam too.
        assertEquals(bg, params.navigationBarDividerColor ?: 0)

        val attrs = hub().theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.statusBarColor, android.R.attr.navigationBarColor),
        )
        assertEquals(theme, attrs.getColor(0, 0))
        assertEquals(bg, attrs.getColor(1, 0))
        attrs.recycle()
    }

    /**
     * #130, the seam's second half: #127 took the web surface's backgrounds and
     * left the accent on ServiceBay blue, so a blue primary button stood next to
     * an orange surface. The shell follows the surface — accent included.
     */
    @Test
    fun theShellAccentFollowsTheWebSurfaceRatherThanServiceBay() {
        assertEquals(0xFFF97316.toInt(), ctx().getColor(R.color.solaris_accent))
        assertEquals(0xFFEA580C.toInt(), ctx().getColor(R.color.solaris_accent_dark))
    }

    /**
     * …and only there. A launcher icon, an app-icon shortcut glyph and a widget
     * trigger tile are seen among foreign icons on the Android home screen, not
     * beside the web surface, so they keep the brand blue — as does the brand
     * lockup itself, which is that same icon's mark.
     */
    @Test
    fun theHomeScreenSurfacesKeepTheBrandBlue() {
        val blue = 0xFF3B82F6.toInt()
        assertEquals(blue, ctx().getColor(R.color.solaris_brand_blue))
        assertEquals(blue, ctx().getColor(R.color.ic_launcher_background))
        assertEquals(blue, cloud.dopp.solaris.realtime.NOTIF_ACCENT)
    }

    // --- 7. below the status bar, not under it (#130) -------------------------

    /**
     * targetSdk 35 draws the window edge-to-edge, and nothing consumed the insets:
     * the status line sat at the height of the system clock. The root adds the
     * system-bar insets onto its own padding.
     */
    @Test
    fun theContentStartsBelowTheSystemBars() {
        paired()
        val a = hub()
        val root = a.findViewById<View>(R.id.home_root)
        val before = root.paddingTop
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, 96, 0, 48),
            )
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertEquals(before + 96, root.paddingTop)
        assertEquals(48, root.paddingBottom)
    }
}
