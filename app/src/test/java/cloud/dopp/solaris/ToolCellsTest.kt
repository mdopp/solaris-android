package cloud.dopp.solaris

import cloud.dopp.solaris.data.ToolCellSchema
import cloud.dopp.solaris.data.ToolDefs
import cloud.dopp.solaris.widget.ToolCell
import cloud.dopp.solaris.widget.ToolCells
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The schema→cell mapper (#70) — the piece that makes the tool widget generic.
 * Every real shipped schema must map its items correctly with **no per-tool code**,
 * and a plugin that ships something unexpected must lose a line, not crash the
 * home screen.
 *
 * Robolectric only for `org.json`; the mapper itself is Android-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCellsTest {

    private fun schema(json: String): ToolCellSchema = ToolDefs.parseSchema(JSONObject(json))

    private fun map(
        schemaJson: String,
        rowJson: String,
        itemIdField: String? = null,
    ): ToolCell? = ToolCells.map(
        schema(schemaJson), JSONObject(rowJson), itemIdField = itemIdField,
    )

    // --- the six real shipped schemas ----------------------------------------

    @Test
    fun taskSchemaMapsTitleAndDue() {
        val cell = map(
            """{"title":"title","meta":["due"]}""",
            """{"entity_id":"task.42","title":"Müll rausbringen","due":"2026-08-25"}""",
            itemIdField = "entity_id",
        )!!
        assertEquals("Müll rausbringen", cell.title)
        assertEquals("2026-08-25", cell.meta)
        assertEquals("task.42", cell.itemId)
        assertNull(cell.subtitle)
        assertNull(cell.badge)
    }

    @Test
    fun contactsSchemaJoinsBothMetaFields() {
        val cell = map(
            """{"title":"name","meta":["phone","email"]}""",
            """{"id":"p7","name":"Anna","phone":"0170 1234","email":"anna@example.org"}""",
            itemIdField = "id",
        )!!
        assertEquals("Anna", cell.title)
        assertEquals("0170 1234${ToolCells.META_SEP}anna@example.org", cell.meta)
        assertEquals("p7", cell.itemId)
    }

    @Test
    fun docSchemaMapsTitleAndCategory() {
        val cell = map(
            """{"title":"title","meta":["category"]}""",
            """{"title":"Stromvertrag","category":"Verträge"}""",
        )!!
        assertEquals("Stromvertrag", cell.title)
        assertEquals("Verträge", cell.meta)
    }

    /** A photo's `people` is a list field — bounded and comma-joined. */
    @Test
    fun photoSchemaFlattensTheListField() {
        val cell = map(
            """{"title":"name","meta":["people"]}""",
            """{"name":"Strand.jpg","people":["Anna","Ben","Cem"]}""",
        )!!
        assertEquals("Strand.jpg", cell.title)
        assertEquals("Anna, Ben, Cem", cell.meta)
    }

    @Test
    fun noteSchemaIsTitleOnly() {
        val cell = map("""{"title":"label"}""", """{"label":"Einkaufen"}""")!!
        assertEquals("Einkaufen", cell.title)
        assertNull(cell.meta)
    }

    /** An empty schema can never produce a row — that's why such tools aren't offered. */
    @Test
    fun emptySchemaYieldsNoRow() {
        assertNull(map("{}", """{"label":"egal","state":"on"}"""))
        assertTrue(ToolCells.mapAll(
            cloud.dopp.solaris.data.ToolDef(
                id = "energy", label = "Energie", apiPath = "/portal/energy",
                searchPath = null, actions = emptyList(), schema = ToolCellSchema.EMPTY,
            ),
            listOf(JSONObject("""{"label":"x"}""")),
        ).isEmpty())
    }

    // --- the richer roles -----------------------------------------------------

    @Test
    fun subtitleBadgeAndIconRolesAreMapped() {
        val cell = map(
            """{"title":"name","subtitle":"role","meta":["phone"],"badge":"state"}""",
            """{"name":"Anna","role":"Mieterin","phone":"0170","state":"aktiv"}""",
        )!!
        assertEquals("Anna", cell.title)
        assertEquals("Mieterin", cell.subtitle)
        assertEquals("0170", cell.meta)
        assertEquals("aktiv", cell.badge)
    }

    @Test
    fun declaredActionsRideAlongOnTheCell() {
        val cell = ToolCells.map(
            schema("""{"title":"title","actions":["task.set_status"]}"""),
            JSONObject("""{"title":"Müll"}"""),
            declaredActions = listOf("task.set_status", "task.add"),
        )!!
        assertEquals(listOf("task.set_status"), cell.actions)
    }

    // --- degradation ----------------------------------------------------------

    @Test
    fun missingTitleFieldDropsTheRow() {
        assertNull(map("""{"title":"title","meta":["due"]}""", """{"due":"morgen"}"""))
        assertNull(map("""{"title":"title"}""", """{"title":"   "}"""))
        assertNull(map("""{"title":"title"}""", """{"title":null}"""))
    }

    @Test
    fun missingMetaFieldsAreSkippedNotBlank() {
        val cell = map(
            """{"title":"name","meta":["phone","email"]}""",
            """{"name":"Ben","email":"ben@example.org"}""",
        )!!
        assertEquals("ben@example.org", cell.meta)
    }

    @Test
    fun fieldTypesOutsideTheClosedSetAreSkipped() {
        // A nested object has no sensible one-line rendering — leave the slot out
        // rather than dumping JSON into the widget.
        val cell = map(
            """{"title":"name","meta":["nested"],"badge":"flag"}""",
            """{"name":"X","nested":{"a":1},"flag":true}""",
        )!!
        assertNull(cell.meta)
        assertEquals("ja", cell.badge)
    }

    @Test
    fun numbersRenderWithoutTrailingZero() {
        val cell = map(
            """{"title":"name","meta":["count","ratio"]}""",
            """{"name":"X","count":3.0,"ratio":1.5}""",
        )!!
        assertEquals("3${ToolCells.META_SEP}1.5", cell.meta)
    }

    @Test
    fun metaLineIsBounded() {
        val cell = map(
            """{"title":"n","meta":["a","b","c","d","e"]}""",
            """{"n":"X","a":"1","b":"2","c":"3","d":"4","e":"5"}""",
        )!!
        assertEquals(ToolCells.META_MAX, cell.meta!!.split(ToolCells.META_SEP).size)
    }

    @Test
    fun mapAllDropsUnusableRowsAndKeepsTheRest() {
        val def = cloud.dopp.solaris.data.ToolDef(
            id = "task", label = "Aufgabe", apiPath = "/portal/tasks",
            searchPath = null, actions = emptyList(),
            schema = schema("""{"title":"title","meta":["due"]}"""),
        )
        val rows = ToolDefs.rows(
            """{"ok":true,"tasks":[{"title":"A","due":"heute"},{"due":"morgen"},{"title":"B"}]}""",
        )
        assertEquals(listOf("A", "B"), ToolCells.mapAll(def, rows).map { it.title })
    }

    // --- row actions (#90, tool-action-params) --------------------------------

    /** The real `.task` declaration: `$id` reads the row, `"done"` is a literal. */
    private val taskParams = mapOf(
        "task.set_status" to mapOf("entity_id" to "\$id", "status" to "done"),
    )

    @Test
    fun taskRowResolvesItsCallbackBodyFromTheCatalog() {
        val cell = ToolCells.map(
            schema("""{"title":"title","meta":["due"],"actions":["task.set_status"]}"""),
            JSONObject("""{"id":"t42","title":"Müll rausbringen","due":"2026-08-25"}"""),
            declaredActions = listOf("task.set_status", "task.add"),
            actionParams = taskParams,
        )!!
        assertEquals("task.set_status", cell.actionId)
        val params = JSONObject(cell.actionParams!!)
        assertEquals("t42", params.getString("entity_id"))
        assertEquals("done", params.getString("status"))
    }

    /** A row missing a `$`-referenced field gets no button — never a half body. */
    @Test
    fun rowWithoutTheReferencedFieldGetsNoButton() {
        val cell = ToolCells.map(
            schema("""{"title":"title","actions":["task.set_status"]}"""),
            JSONObject("""{"title":"Müll"}"""),
            declaredActions = listOf("task.set_status"),
            actionParams = taskParams,
        )!!
        assertNull(cell.actionId)
        assertNull(cell.actionParams)
    }

    /** Today's other tools declare no params at all — they behave as before. */
    @Test
    fun toolWithoutDeclaredParamsGetsNoButton() {
        val cell = ToolCells.map(
            schema("""{"title":"name","actions":["contact.add"]}"""),
            JSONObject("""{"id":"p7","name":"Anna"}"""),
            declaredActions = listOf("contact.add"),
        )!!
        assertEquals(listOf("contact.add"), cell.actions)
        assertNull(cell.actionId)
    }

    /** A field value keeps its raw JSON type — the server wants the id, not "ja". */
    @Test
    fun fieldValuesAreResolvedRawNotAsDisplayText() {
        val params = ToolCells.resolveParams(
            mapOf("n" to "\$count", "flag" to "\$done", "lit" to "done"),
            JSONObject("""{"count":7,"done":true}"""),
        )!!
        assertEquals(7, params.getInt("n"))
        assertTrue(params.getBoolean("flag"))
        assertEquals("done", params.getString("lit"))
    }

    @Test
    fun resolveParamsRefusesEmptyAndUnusableSources() {
        val row = JSONObject("""{"id":"x","nested":{"a":1}}""")
        assertNull(ToolCells.resolveParams(emptyMap(), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$"), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$missing"), row))
        assertNull(ToolCells.resolveParams(mapOf("p" to "\$nested"), row))
    }

    /** An id the schema doesn't name (or the def doesn't declare) is never offered. */
    @Test
    fun onlySchemaDeclaredActionsBecomeTheButton() {
        assertNull(ToolCells.resolveAction(emptyList(), JSONObject("""{"id":"t"}"""), taskParams))
        assertEquals(
            "task.set_status",
            ToolCells.resolveAction(
                listOf("task.add", "task.set_status"),
                JSONObject("""{"id":"t"}"""),
                taskParams,
            )!!.first,
        )
    }

    // --- the declared item id field (#107) ------------------------------------

    /**
     * The core rule of #107: the id comes out of the field the **def named**. The
     * task fixture carries both an `entity_id` and an `id`, and which one the row
     * offers is decided by the declaration alone — that is what a guess list can
     * never get right for every tool at once.
     */
    @Test
    fun theItemIdComesOutOfTheDeclaredFieldOnly() {
        val row = """{"title":"Müll","entity_id":"task.42","id":"77"}"""
        assertEquals("77", map("""{"title":"title"}""", row, itemIdField = "id")!!.itemId)
        assertEquals(
            "task.42",
            map("""{"title":"title"}""", row, itemIdField = "entity_id")!!.itemId,
        )
    }

    /** A tool that declares no field (`note`, `home`, `energy`) offers no id. */
    @Test
    fun aToolWithoutADeclaredFieldOffersNoItemId() {
        val row = """{"title":"Müll","entity_id":"task.42","id":"77"}"""
        assertNull(map("""{"title":"title"}""", row)!!.itemId)
        assertNull(map("""{"title":"title"}""", row, itemIdField = "")!!.itemId)
    }

    /** Declared but absent, blank, or not a scalar → no id, and no second try. */
    @Test
    fun aMissingFieldValueYieldsNoItemIdRatherThanAFallback() {
        val schema = """{"title":"title"}"""
        assertNull(map(schema, """{"title":"A","id":"7"}""", itemIdField = "uid")!!.itemId)
        assertNull(map(schema, """{"title":"A","uid":"  "}""", itemIdField = "uid")!!.itemId)
        assertNull(map(schema, """{"title":"A","uid":null}""", itemIdField = "uid")!!.itemId)
        assertNull(map(schema, """{"title":"A","uid":{"x":1}}""", itemIdField = "uid")!!.itemId)
        // A numeric id is an id — it just has to become a string to ride a route.
        assertEquals("42", map(schema, """{"title":"A","uid":42}""", itemIdField = "uid")!!.itemId)
    }

    /** The whole def path: `mapAll` hands each row the tool's own declaration. */
    @Test
    fun mapAllReadsTheFieldOffTheDef() {
        val defs = ToolDefs.parseCatalog(
            """{"defs":[{"tool-id":"doc","tool-cell-schema":{"title":"title"},
               "tool-item-id-field":"entity_id"}]}""",
        )
        val cells = ToolCells.mapAll(
            defs.single(),
            listOf(JSONObject("""{"title":"Rechnung","entity_id":"doc.2026-08.rechnung"}""")),
        )
        assertEquals("doc.2026-08.rechnung", cells.single().itemId)
    }

    // --- cache codec ----------------------------------------------------------

    @Test
    fun cellsSurviveTheCacheRoundTrip() {
        val cells = listOf(
            ToolCell(itemId = "1", title = "A", subtitle = "s", meta = "m", badge = "b"),
            ToolCell(itemId = null, title = "B", subtitle = null, meta = null, badge = null),
        )
        val back = ToolCells.decode(ToolCells.encode(cells))
        assertEquals(2, back.size)
        assertEquals(cells[0].copy(actions = emptyList()), back[0])
        assertNull("a row without an id keeps none", back[1].itemId)
        assertEquals("B", back[1].title)
        assertNull(back[1].meta)
    }

    /** The button must survive a cold redraw — the tap does no catalog lookup. */
    @Test
    fun theResolvedActionSurvivesTheCacheRoundTrip() {
        val cells = listOf(
            ToolCell(
                itemId = "t42", title = "Müll", subtitle = null, meta = "morgen", badge = null,
                actionId = "task.set_status",
                actionParams = """{"entity_id":"t42","status":"done"}""",
            ),
            ToolCell(itemId = "p7", title = "Anna", subtitle = null, meta = null, badge = null),
        )
        val back = ToolCells.decode(ToolCells.encode(cells))
        assertEquals("task.set_status", back[0].actionId)
        assertEquals("t42", JSONObject(back[0].actionParams!!).getString("entity_id"))
        // A row without an action stays without one — no empty placeholder button.
        assertNull(back[1].actionId)
        assertNull(back[1].actionParams)
    }

    @Test
    fun malformedCacheDecodesToEmpty() {
        assertTrue(ToolCells.decode(null).isEmpty())
        assertTrue(ToolCells.decode("").isEmpty())
        assertTrue(ToolCells.decode("{oops").isEmpty())
    }
}
