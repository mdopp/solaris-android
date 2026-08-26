package cloud.dopp.solaris

import android.appwidget.AppWidgetManager
import android.view.LayoutInflater
import android.view.View
import cloud.dopp.solaris.widget.ToolActionActivity
import cloud.dopp.solaris.widget.PwaLauncher
import cloud.dopp.solaris.widget.ToolCell
import cloud.dopp.solaris.widget.ToolRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The tool row's two tap targets (#90). On v2.32.1 *and* v2.32.2 the action button
 * ran the PWA instead of the action: the params were there and the button was
 * visible and 48dp and clickable, yet the press ended up on `tw_item_root`, whose
 * fill-in carries `EXTRA_PATH`, and [ToolActionActivity] does exactly what such an
 * intent asks for.
 *
 * Two attempts lost that contest; the third removes it. The root carries **no**
 * fill-in any more — the PWA fill-in sits on the text column and the badge, the
 * action fill-in on the button, and those are **siblings**. So three halves are
 * guarded here: the **intents** stay distinguishable (only the button's carries
 * `EXTRA_ACTION_ID`), the **structure** keeps no fill-in on an ancestor of the
 * button, and the **layout** keeps the button a 48dp clickable target that no
 * longer steals focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRowTapTest {

    private fun cell(actionId: String? = "task.done", params: String? = """{"id":"task.42"}""") =
        ToolCell(
            id = "task.42",
            title = "Müll rausbringen",
            subtitle = null,
            meta = null,
            badge = null,
            actionId = actionId,
            actionParams = params,
        )

    // --- the intents ----------------------------------------------------------

    /** The rule the whole ticket turns on. */
    @Test
    fun onlyTheButtonsFillInCarriesTheActionId() {
        val body = ToolRow.bodyFillIn()
        val action = ToolRow.actionFillIn(cell(), 7)!!

        assertNull(
            "a body tap must never be able to run the action",
            body.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID),
        )
        assertEquals("task.done", action.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
    }

    /** …and the button's tap must not fall through to the PWA path either. */
    @Test
    fun theTwoFillInsAreDistinguishable() {
        val body = ToolRow.bodyFillIn()
        val action = ToolRow.actionFillIn(cell(), 7)!!

        assertNotEquals(
            "ToolActionActivity tells the two taps apart by their extras alone",
            body.extras?.keySet(), action.extras?.keySet(),
        )
        assertNull(
            "the action carries no PWA route to fall back to",
            action.getStringExtra(ToolActionActivity.EXTRA_PATH),
        )
        assertEquals(
            PwaLauncher.Routes.ROOT,
            body.getStringExtra(ToolActionActivity.EXTRA_PATH),
        )
    }

    /**
     * #107 asked for the row tap to open *that row's* item. The PWA has no route
     * for one: `openPortal` knows `device/<id>`, `camera/<id>`,
     * `servicebay/approvals/<id>` and `<tool-id>/new`, and everything else falls
     * through to the chat view. Inventing an address would trade a tap that lands
     * on the start page for one that lands nowhere, so the target stays the root
     * until solarisbay declares an item route (mdopp/solarisbay#1256). This test
     * is the reminder: when that lands, [ToolRow.bodyPath] is the one place to
     * change, and this assertion is the one to rewrite.
     */
    @Test
    fun theRowTapStaysOnTheRootUntilAnItemRouteExists() {
        assertEquals(PwaLauncher.Routes.ROOT, ToolRow.bodyPath())
    }

    @Test
    fun theActionCarriesEverythingTheCallbackNeeds() {
        val action = ToolRow.actionFillIn(cell(), 7)!!
        assertEquals("""{"id":"task.42"}""", action.getStringExtra(ToolActionActivity.EXTRA_PARAMS))
        assertEquals("Müll rausbringen", action.getStringExtra(ToolActionActivity.EXTRA_TITLE))
        assertEquals(7, action.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** No action declared → no fill-in, and the caller hides the button. */
    @Test
    fun aRowWithoutAnActionOffersNoButton() {
        assertNull(ToolRow.actionFillIn(cell(actionId = null), 7))
        assertNull(ToolRow.actionFillIn(cell(actionId = "  "), 7))
    }

    // --- the structure: nobody has to out-bubble an ancestor ------------------

    /**
     * The correction the third attempt turns on: the row root is no longer a tap
     * target, so the button never competes with its own ancestor.
     */
    @Test
    fun theRowRootCarriesNoFillIn() {
        assertFalse(
            "tw_item_root must not be a tap target — the button is its descendant (#90)",
            R.id.tw_item_root in ToolRow.BODY_TAP_TARGETS,
        )
        assertTrue("the row body still has a tap target", ToolRow.BODY_TAP_TARGETS.isNotEmpty())
        assertFalse(
            "the action button carries its own fill-in, not the body's",
            R.id.tw_item_action in ToolRow.BODY_TAP_TARGETS,
        )
    }

    /** …and "not an ancestor" is a property of the layout, so assert it there. */
    @Test
    fun theTapTargetsAreSiblingsOfTheActionButton() {
        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        val button = row.findViewById<View>(R.id.tw_item_action)

        for (id in ToolRow.BODY_TAP_TARGETS) {
            val target = row.findViewById<View>(id)
            assertNotNull("BODY_TAP_TARGETS names a view the row layout has", target)
            assertEquals(
                "a PWA tap target must be a sibling of the button, never its ancestor (#90)",
                button.parent, target.parent,
            )
        }
    }

    // --- the layout: the button has to *get* the touch ------------------------

    /**
     * A non-clickable child of a clickable row root never consumes the press —
     * that was the first suspicion. It stays true regardless, and costs nothing.
     */
    @Test
    fun theActionButtonClaimsTheTouch() {
        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        val button = row.findViewById<View>(R.id.tw_item_action)

        assertTrue("tw_item_action must be clickable (#90)", button.isClickable)
    }

    /**
     * A focusable child in a ListView row is a known way to break click delivery,
     * and nothing here needs the focus — the button must not ask for it.
     */
    @Test
    fun theActionButtonDoesNotTakeFocus() {
        val xml = File(locate("src/main"), "res/layout/item_tool_row.xml").readText()
        val decl = xml.substringAfter("@+id/tw_item_action").substringBefore("/>")

        assertFalse(
            "drop android:focusable on tw_item_action (#90)",
            decl.contains("android:focusable"),
        )
    }

    /** Android's minimum touch target, asserted on the declaration itself. */
    @Test
    fun theActionButtonIsAtLeastFortyEightDpOfTouch() {
        val xml = File(locate("src/main"), "res/layout/item_tool_row.xml").readText()
        val decl = xml.substringAfter("@+id/tw_item_action").substringBefore("/>")

        val dps = Regex("""android:(layout_width|layout_height|minWidth|minHeight)="(\d+)dp"""")
            .findAll(decl).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue("declare a width for tw_item_action", dps.isNotEmpty())
        for ((attr, dp) in dps) {
            assertTrue("$attr is ${dp}dp — under the 48dp target (#90)", dp >= 48)
        }
        assertTrue("minHeight keeps the target 48dp tall in a short row", (dps["minHeight"] ?: 0) >= 48)
    }

    /**
     * …and it costs the row no visible height: the vertical padding moved off the
     * root onto the text column, so the 48dp button spans the row instead of
     * stacking on top of the padding.
     */
    @Test
    fun theBiggerTargetDoesNotBloatTheRow() {
        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        assertEquals(
            "row padding belongs to the text column now (#90)",
            0, row.paddingTop + row.paddingBottom,
        )
    }

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
