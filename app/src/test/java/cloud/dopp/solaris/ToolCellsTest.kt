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

    private fun map(schemaJson: String, rowJson: String): ToolCell? =
        ToolCells.map(schema(schemaJson), JSONObject(rowJson))

    // --- the six real shipped schemas ----------------------------------------

    @Test
    fun taskSchemaMapsTitleAndDue() {
        val cell = map(
            """{"title":"title","meta":["due"]}""",
            """{"entity_id":"task.42","title":"Müll rausbringen","due":"2026-08-25"}""",
        )!!
        assertEquals("Müll rausbringen", cell.title)
        assertEquals("2026-08-25", cell.meta)
        assertEquals("task.42", cell.id)
        assertNull(cell.subtitle)
        assertNull(cell.badge)
    }

    @Test
    fun contactsSchemaJoinsBothMetaFields() {
        val cell = map(
            """{"title":"name","meta":["phone","email"]}""",
            """{"id":"p7","name":"Anna","phone":"0170 1234","email":"anna@example.org"}""",
        )!!
        assertEquals("Anna", cell.title)
        assertEquals("0170 1234${ToolCells.META_SEP}anna@example.org", cell.meta)
        assertEquals("p7", cell.id)
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

    // --- cache codec ----------------------------------------------------------

    @Test
    fun cellsSurviveTheCacheRoundTrip() {
        val cells = listOf(
            ToolCell(id = "1", title = "A", subtitle = "s", meta = "m", badge = "b"),
            ToolCell(id = "", title = "B", subtitle = null, meta = null, badge = null),
        )
        val back = ToolCells.decode(ToolCells.encode(cells))
        assertEquals(2, back.size)
        assertEquals(cells[0].copy(actions = emptyList()), back[0])
        assertEquals("B", back[1].title)
        assertNull(back[1].meta)
    }

    @Test
    fun malformedCacheDecodesToEmpty() {
        assertTrue(ToolCells.decode(null).isEmpty())
        assertTrue(ToolCells.decode("").isEmpty())
        assertTrue(ToolCells.decode("{oops").isEmpty())
    }
}
