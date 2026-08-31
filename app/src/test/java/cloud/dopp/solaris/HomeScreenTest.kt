package cloud.dopp.solaris

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

    /**
     * #132: the mark looked crammed into the small halo because the two were
     * shrunk by different factors. What has to survive the shrink is the *ratio*
     * of mark to halo — the two presentations are one mark at two sizes.
     */
    @Test
    fun theCompactLockupKeepsTheWordmarksRatioToItsHalo() {
        val first = hub()
        val fullGlow = first.findViewById<View>(R.id.brand_glow).layoutParams.height.toFloat()
        val fullWord = first.findViewById<TextView>(R.id.brand_word_start).textSize
        val fullFigure = first.findViewById<View>(R.id.brand_figure).layoutParams.height.toFloat()

        paired()
        val a = hub()
        val glow = a.findViewById<View>(R.id.brand_glow).layoutParams.height.toFloat()
        val word = a.findViewById<TextView>(R.id.brand_word_start).textSize
        val figure = a.findViewById<View>(R.id.brand_figure).layoutParams.height.toFloat()

        assertTrue("the paired lockup is the smaller one", glow < fullGlow)
        assertEquals("wordmark to halo", fullWord / fullGlow, word / glow, 0.005f)
        assertEquals("figure to halo", fullFigure / fullGlow, figure / glow, 0.01f)
    }

    /**
     * #132: the halo's gradient radius was a fixed 110dp, which only suited the
     * 190dp first-run circle — drawn at 112dp the fade had reached half its way
     * at the edge and the disc cut off hard. Relative to the drawable, it ends at
     * the edge whatever size the lockup is drawn at.
     */
    @Test
    fun theGlowFadesOutAtItsEdgeAtEverySize() {
        val glow = ctx().getDrawable(R.drawable.hero_glow) as GradientDrawable
        glow.setBounds(0, 0, 112, 112)
        assertEquals(56f, glow.gradientRadius, 1f)
        glow.setBounds(0, 0, 190, 190)
        assertEquals(95f, glow.gradientRadius, 1f)
        // …and it fades into transparent BLUE: #00000000 leaves a grey halo on the
        // dark background, which is why the end colour is not simply "transparent".
        val colors = glow.colors!!
        assertEquals(0x003B82F6, colors[colors.size - 1])
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

    // --- 3. the way into Solaris (#127/#132) ----------------------------------

    /**
     * #127 made this the filled, full-width primary right under the status line;
     * on the device it was the loudest thing on the screen and competed with the
     * wordmark above it (#132), so it moved down to the foot. The correction that
     * must NOT come back with it is #127's own finding: down does not mean turned
     * into a footer text link. It stays a real, filled button — visibly a
     * different thing from the quiet sign-out link it now sits beside.
     */
    @Test
    fun theWayIntoSolarisSitsAtTheFootButStaysAButton() {
        paired()
        val a = hub()
        val section = a.findViewById<LinearLayout>(R.id.connected_section)
        val open = section.indexOfChild(a.findViewById(R.id.open_btn))
        val widgets = section.indexOfChild(a.findViewById(R.id.widgets_teaser_card))
        assertTrue("the entry moved below the cards (#132)", open > widgets)

        val btn = a.findViewById<View>(R.id.open_btn)
        assertTrue("it may get quieter, not invisible — a link is not a button", btn is Button)
        assertNotNull("a text link has no button background", btn.background)
        // …and it must stay distinguishable from "Abmelden" next to it.
        assertTrue(a.findViewById<View>(R.id.logout_btn) !is Button)

        btn.performClick()
        assertNotNull("the button switches the view inside the app", startedOn(SolarisSurfaceActivity::class.java))
    }

    /** #132: two secondary lines, one row — sign out and the version line. */
    @Test
    fun signOutAndTheVersionLineShareOneRow() {
        paired()
        val a = hub()
        val out = a.findViewById<View>(R.id.logout_btn)
        val version = a.findViewById<View>(R.id.app_version)
        assertEquals("both are secondary and share a line", out.parent, version.parent)
        val row = out.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
        assertTrue(visible(out) && visible(version))
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
        // The footer row is shared with the paired state (#132): the version line
        // — and with it diagnostics (#110) — stays, sign out has nothing to do here.
        assertTrue(visible(a.findViewById(R.id.app_version)))
        assertTrue(!visible(a.findViewById(R.id.logout_btn)))
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

    // --- 8. where the leftover space sits (#134) -------------------------------

    /** Measure and lay the screen out at a given viewport height. */
    private fun laidOut(a: OnboardingHomeActivity, heightPx: Int): LinearLayout {
        val root = a.findViewById<ScrollView>(R.id.home_root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, heightPx)
        return root.getChildAt(0) as LinearLayout
    }

    /**
     * #134: the content started at the top and left a large empty area under the
     * footer — the air sat where it does nothing. It belongs under the logo: the
     * column is anchored at the bottom, the slack falls between the brand block
     * and the rest.
     */
    @Test
    fun theLeftoverSpaceFallsUnderTheLogoNotUnderTheFooter() {
        paired()
        val a = hub()
        val column = laidOut(a, 3000)
        val spacer = a.findViewById<View>(R.id.brand_spacer)

        assertTrue("the gap belongs between the wordmark and the rest", spacer.height > 0)
        assertTrue(
            "…and it is the space that used to sit under the footer",
            column.indexOfChild(spacer) > column.indexOfChild(a.findViewById(R.id.brand_block)),
        )
        assertTrue(
            "the spacer stays above everything the operator reads",
            column.indexOfChild(spacer) < column.indexOfChild(a.findViewById(R.id.connected_section)),
        )
        // Nothing but the column's own bottom padding is left under the footer.
        val footer = a.findViewById<View>(R.id.home_footer)
        assertEquals(
            "the last row ends at the foot of the screen",
            column.height - column.paddingBottom,
            footer.bottom,
        )
    }

    /**
     * The limit that matters more than the gap: on a screen with no slack the
     * spacing collapses instead of displacing content — #126's prioritisation
     * survives, and nothing gets clipped or pushed out of reach.
     */
    @Test
    fun onAShortScreenTheGapCollapsesRatherThanPushingContentAway() {
        paired()
        val a = hub()
        val column = laidOut(a, 320)
        val spacer = a.findViewById<View>(R.id.brand_spacer)
        val footer = a.findViewById<View>(R.id.home_footer)

        assertEquals("no slack, no gap", 0, spacer.height)
        assertTrue("the content is taller than the viewport, so it scrolls", column.height > 320)
        assertTrue("…and the footer is still inside it", footer.bottom <= column.height)
        assertTrue("nothing is collapsed away with the gap", footer.height > 0)
    }
}
