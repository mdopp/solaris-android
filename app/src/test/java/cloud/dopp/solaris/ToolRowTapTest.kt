package cloud.dopp.solaris

import android.appwidget.AppWidgetManager
import android.view.LayoutInflater
import android.view.View
import cloud.dopp.solaris.widget.ToolActionActivity
import cloud.dopp.solaris.widget.PwaLauncher
import cloud.dopp.solaris.widget.ToolCell
import cloud.dopp.solaris.widget.ToolRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The tool row's two tap targets (#90). On v2.32.1 the action button ran the PWA
 * instead of the action: the params were there and the button was visible, but the
 * press never stopped at `tw_item_action` — it bubbled up to `tw_item_root`, whose
 * fill-in carries `EXTRA_PATH`, and [ToolActionActivity] does exactly what such an
 * intent asks for.
 *
 * So there are two halves to guard, and both are asserted here: the **intents**
 * must stay distinguishable (only the button's carries `EXTRA_ACTION_ID`), and the
 * **layout** must let the button claim the touch (explicitly clickable, at least
 * the platform's 48dp target).
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

    // --- the layout: the button has to *get* the touch ------------------------

    /**
     * A non-clickable child of a clickable row root never consumes the press —
     * that is the whole defect. `clickable` is what stops it at the button.
     */
    @Test
    fun theActionButtonClaimsTheTouch() {
        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        val button = row.findViewById<View>(R.id.tw_item_action)

        assertTrue("tw_item_action must be clickable (#90)", button.isClickable)
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
