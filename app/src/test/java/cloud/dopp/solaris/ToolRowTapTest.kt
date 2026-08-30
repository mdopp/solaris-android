package cloud.dopp.solaris

import android.appwidget.AppWidgetManager
import android.view.LayoutInflater
import android.view.ViewGroup
import cloud.dopp.solaris.widget.ActionRow
import cloud.dopp.solaris.widget.ActionSheets
import cloud.dopp.solaris.widget.PwaLauncher
import cloud.dopp.solaris.widget.ToolActionActivity
import cloud.dopp.solaris.widget.ToolCell
import cloud.dopp.solaris.widget.ToolRow
import cloud.dopp.solaris.widget.ToolRowChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * What a tap on a tool widget row offers (#90/#107).
 *
 * #90 was attempted three times as a *second click target* inside the row —
 * attributes (clickable, 48dp), then structure (no fill-in on the root, text
 * column and button as siblings) — and every attempt lost the press on the
 * device. The last one is disproved by the app's own code path: [ToolActionActivity]
 * opens the PWA only when no action id arrived, so "Solaris opened" means the
 * button's fill-in never reached it. This launcher delivers one target per
 * collection row, so the fourth attempt stops guessing and moves the choice into
 * the sheet the row tap opens — the lock chooser's pattern (#92), the one proven
 * on this device.
 *
 * Asserted here, all of it pure: which entries a row offers (a row with no
 * action, a tool with no item field, a field the row doesn't carry), which route
 * the open entry aims at, that the sheet is the *shared* one (#113) rather than a
 * fourth dialog form, and that the check mark is **gone from the layout** rather
 * than hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRowTapTest {

    private fun cell(
        actionId: String? = "task.set_status",
        params: String? = """{"entity_id":"task.42","status":"done"}""",
        itemId: String? = "task.42",
    ) = ToolCell(
        itemId = itemId,
        title = "Müll rausbringen",
        subtitle = null,
        meta = null,
        badge = null,
        actionId = actionId,
        actionParams = params,
    )

    // --- what the row offers --------------------------------------------------

    /** The everyday case: `.task` declares both, so the sheet has both entries. */
    @Test
    fun aTaskRowOffersTheActionAndTheItem() {
        val path = ToolRow.itemPath("task", cell().itemId)
        assertEquals(
            listOf(ToolRowChoice.RUN, ToolRowChoice.OPEN),
            ToolRow.choices(hasAction = true, itemPath = path),
        )
    }

    /** A row whose tool declares no `tool-action-params` offers only the item. */
    @Test
    fun aRowWithoutAnActionOffersOnlyTheItem() {
        val path = ToolRow.itemPath("doc", "doc.2026-08.rechnung")
        assertEquals(
            listOf(ToolRowChoice.OPEN),
            ToolRow.choices(hasAction = false, itemPath = path),
        )
    }

    /**
     * `note`, `home` and `energy` declare no `tool-item-id-field`, so their rows
     * get **no** open entry — an address is not invented for a tool that never
     * claimed to have one (#107).
     */
    @Test
    fun aToolWithoutAnItemFieldOffersNoOpenEntry() {
        assertNull("no declared field ⇒ no item route", ToolRow.itemPath("note", null))
        assertTrue(ToolRow.choices(hasAction = false, itemPath = null).isEmpty())
        assertEquals(
            listOf(ToolRowChoice.RUN),
            ToolRow.choices(hasAction = true, itemPath = null),
        )
    }

    /** A declared field the row doesn't carry is the same answer: no open entry. */
    @Test
    fun aMissingFieldValueOffersNoOpenEntry() {
        assertNull(ToolRow.itemPath("task", null))
        assertNull(ToolRow.itemPath("task", "   "))
        // …and so is a tool id the widget never got bound to.
        assertNull(ToolRow.itemPath(null, "task.42"))
    }

    /**
     * A tap that could only navigate just navigates; a tap that could tick off a
     * task asks first. That asymmetry is the whole justification for the extra
     * tap — it buys deliberateness, not ceremony.
     */
    @Test
    fun onlyATapThatCouldChangeSomethingAsksFirst() {
        assertTrue(ToolRow.asksFirst(listOf(ToolRowChoice.RUN)))
        assertTrue(ToolRow.asksFirst(listOf(ToolRowChoice.RUN, ToolRowChoice.OPEN)))
        assertFalse(ToolRow.asksFirst(listOf(ToolRowChoice.OPEN)))
        assertFalse(ToolRow.asksFirst(emptyList()))
    }

    // --- the sheet is the shared one (#113) -----------------------------------

    @Test
    fun theSheetListsTheChoicesInOrderAndEndsWithCancel() {
        val choices = listOf(ToolRowChoice.RUN, ToolRowChoice.OPEN)
        val sheet = ActionSheets.toolRow(choices)

        assertEquals(2, sheet.items.size)
        assertEquals(R.string.tool_action_do, sheet.items[0].labelRes)
        assertEquals(R.string.tool_row_open, sheet.items[1].labelRes)
        // Abbrechen is the footer here exactly as in every other dialog (#113).
        assertEquals(ActionRow.Cancel, sheet.rows.last())
        assertEquals(sheet.rows.lastIndex, sheet.cancelIndex)
        // The pick index maps straight back onto the choice list.
        sheet.rows.filterIsInstance<ActionRow.Pick>().forEachIndexed { i, pick ->
            assertEquals(choices[i].labelRes, pick.item.labelRes)
            assertEquals(i, pick.index)
        }
    }

    /** Nothing a plugin declares is treated as dangerous — the server gates that. */
    @Test
    fun theToolSheetCarriesNoDangerousEntry() {
        assertFalse(
            ActionSheets.toolRow(listOf(ToolRowChoice.RUN, ToolRowChoice.OPEN)).hasDangerous,
        )
    }

    // --- the route (#107) -----------------------------------------------------

    /** The literals solarisbay#1256 settled on, spelled out. */
    @Test
    fun theItemRouteIsToolThenItemThenId() {
        assertEquals("/#/p/task/item/42", PwaLauncher.Routes.toolItem("task", "42"))
        assertEquals(
            "https://chat.dopp.cloud/#/p/task/item/42",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.toolItem("task", "42")),
        )
    }

    /**
     * `item` is its own segment, which is what keeps an id with dots intact —
     * `doc` ids look like this — and what keeps the route from colliding with
     * `#/p/<tool-id>/new` (#1213).
     */
    @Test
    fun anIdWithDotsRidesThroughUntouched() {
        assertEquals(
            "/#/p/doc/item/doc.2026-08.rechnung",
            PwaLauncher.Routes.toolItem("doc", "doc.2026-08.rechnung"),
        )
        assertEquals(
            "/#/p/doc/item/doc.2026-08.rechnung",
            ToolRow.itemPath("doc", "  doc.2026-08.rechnung  "),
        )
    }

    /** Unknown tool, missing id, or an id that would break the route → the start page. */
    @Test
    fun anUnusableIdFallsBackToTheStartPage() {
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem(null, "42"))
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem("task", null))
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem("task", " "))
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem("task", "a/b"))
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem("task", "a b"))
        assertEquals(PwaLauncher.Routes.TOOL_START, PwaLauncher.Routes.toolItem("task", "a#b"))
    }

    // --- the fill-in ----------------------------------------------------------

    /** One fill-in carries everything the tap needs — there is no second one. */
    @Test
    fun theRowsFillInCarriesActionRouteAndTitle() {
        val i = ToolRow.fillIn(cell(), "task", 7)
        assertEquals("task.set_status", i.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
        assertEquals(
            """{"entity_id":"task.42","status":"done"}""",
            i.getStringExtra(ToolActionActivity.EXTRA_PARAMS),
        )
        assertEquals("/#/p/task/item/task.42", i.getStringExtra(ToolActionActivity.EXTRA_PATH))
        assertEquals("Müll rausbringen", i.getStringExtra(ToolActionActivity.EXTRA_TITLE))
        assertEquals(7, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    /** An absent extra is how "this row offers none" travels — not an empty one. */
    @Test
    fun aRowThatOffersNothingCarriesNeitherExtra() {
        val i = ToolRow.fillIn(cell(actionId = null, params = null), "note", 7)
        assertNull(i.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
        assertEquals("/#/p/note/item/task.42", i.getStringExtra(ToolActionActivity.EXTRA_PATH))

        val bare = ToolRow.fillIn(cell(actionId = "  ", params = null, itemId = null), null, 7)
        assertNull(bare.getStringExtra(ToolActionActivity.EXTRA_ACTION_ID))
        assertNull(bare.getStringExtra(ToolActionActivity.EXTRA_PATH))
    }

    // --- the layout: the check mark is gone, not hidden -----------------------

    /**
     * "Removed from the layout, not hidden" is the user's own wording: a hidden
     * view keeps inviting a fifth attempt at making it take the press.
     */
    @Test
    fun theActionButtonIsGoneFromTheLayout() {
        val xml = File(locate("src/main"), "res/layout/item_tool_row.xml").readText()
        assertFalse(
            "the check mark must be removed from item_tool_row.xml, not hidden (#90)",
            xml.contains("@+id/tw_item_action"),
        )
    }

    /** …and with it the second tap target: the row root is the only one left. */
    @Test
    fun theRowHasExactlyOneTapTarget() {
        assertEquals(R.id.tw_item_root, ToolRow.TAP_TARGET)

        val ctx = RuntimeEnvironment.getApplication()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_tool_row, null)
        assertEquals(R.id.tw_item_root, row.id)
        assertFalse("nothing inside the row claims the press", anyChildClickable(row as ViewGroup))
    }

    private fun anyChildClickable(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isClickable) return true
            if (child is ViewGroup && anyChildClickable(child)) return true
        }
        return false
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
