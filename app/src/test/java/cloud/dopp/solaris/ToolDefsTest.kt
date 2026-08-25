package cloud.dopp.solaris

import cloud.dopp.solaris.data.ToolDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `.tool` catalog contract (#70/#72, solarisbay#1021 + ADR 0011). Everything
 * here is pure JSON→model work, so the whole discovery path is locked on the JVM:
 * the `/api/`→`/napi/` prefix swap, the closed cell-role vocabulary, which tools
 * may be offered as a list widget, and how a malformed def degrades.
 *
 * The fixtures are the **real shipped defs** from
 * `templates/solaris/skills/household/<tool>/SKILL.md` — if the server ships a new
 * shape, this test is where it must show up first.
 *
 * Robolectric only for `org.json` (the JVM stub throws); nothing here needs a
 * device or a context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolDefsTest {

    /** The catalog exactly as `GET /napi/defs/tool` serves it today. */
    private val shippedCatalog = """
        {"ok": true, "kind": "tool", "defs": [
          {"id":"task-tool","name":"solaris-task-tool","kind":"tool","tool-id":"task",
           "tool-label":"Aufgabe","command":".task",
           "tool-api-path":"/api/portal/tasks?done=1","tool-search-path":"",
           "tool-compose-path":"#/p/task/new",
           "tool-actions":["task.set_status","task.add","task.update"],
           "tool-cell-schema":{"title":"title","meta":["due"],"actions":["task.set_status"]},
           "tool-action-params":{"task.set_status":{"entity_id":"${'$'}id","status":"done"},
             "task.add":{"title":"${'$'}title","due":"${'$'}due"},
             "task.update":{"entity_id":"${'$'}id","title":"${'$'}title","due":"${'$'}due"}}},
          {"id":"contacts-tool","name":"solaris-contacts-tool","kind":"tool","tool-id":"contacts",
           "tool-label":"Kontakt","tool-api-path":"/api/portal/persons",
           "tool-compose-path":"#/p/contacts/new",
           "tool-actions":["contact.add","person.update"],
           "tool-cell-schema":{"title":"name","meta":["phone","email"]}},
          {"id":"doc-tool","name":"solaris-doc-tool","kind":"tool","tool-id":"doc",
           "tool-label":"Dokument","tool-api-path":"/api/portal/documents/search",
           "tool-compose-path":"#/p/doc/new",
           "tool-actions":["doc.classify"],
           "tool-cell-schema":{"title":"title","meta":["category"]}},
          {"id":"photo-tool","name":"solaris-photo-tool","kind":"tool","tool-id":"photo",
           "tool-label":"Foto","tool-api-path":"/api/photo","tool-actions":[],
           "tool-compose-path":"#/p/photo/new",
           "tool-cell-schema":{"title":"name","meta":["people"]}},
          {"id":"note-tool","name":"solaris-note-tool","kind":"tool","tool-id":"note",
           "tool-label":"Notiz","tool-api-path":"","tool-actions":["note.add"],
           "tool-compose-path":"#/p/note/new",
           "tool-cell-schema":{"title":"label"}},
          {"id":"energy-tool","name":"solaris-energy-tool","kind":"tool","tool-id":"energy",
           "tool-label":"Energie","tool-api-path":"/api/portal/energy","tool-actions":[],
           "tool-cell-schema":{}},
          {"id":"home-tool","name":"solaris-home-tool","kind":"tool","tool-id":"home",
           "tool-label":"Gerät","tool-api-path":"/api/portal/start/addable","tool-actions":[],
           "tool-cell-schema":{}}
        ]}
    """.trimIndent()

    @Test
    fun parsesEveryShippedDef() {
        val defs = ToolDefs.parseCatalog(shippedCatalog)
        assertEquals(
            listOf("task", "contacts", "doc", "photo", "note", "energy", "home"),
            defs.map { it.id },
        )
        assertEquals("Aufgabe", defs.first().label)
    }

    /**
     * The defs declare the **browser** twin (`/api/…`) but the device token only
     * authenticates on `/napi/…` — the app must swap the prefix, keeping the query.
     */
    @Test
    fun apiPrefixIsSwappedForNapi() {
        assertEquals("/portal/tasks?done=1", ToolDefs.napiPath("/api/portal/tasks?done=1"))
        assertEquals(
            "/napi/portal/tasks?done=1",
            ToolDefs.fullNapiPath("/api/portal/tasks?done=1"),
        )
        assertEquals("/napi/photo", ToolDefs.fullNapiPath("/api/photo"))
        assertEquals(
            "/napi/portal/documents/search",
            ToolDefs.fullNapiPath("/api/portal/documents/search"),
        )
    }

    @Test
    fun nativeOrBarePathsSurviveUntouched() {
        assertEquals("/portal/energy", ToolDefs.napiPath("/napi/portal/energy"))
        assertEquals("/portal/energy", ToolDefs.napiPath("/portal/energy"))
        assertEquals("/portal/energy", ToolDefs.napiPath("portal/energy"))
    }

    @Test
    fun missingOrForeignPathsYieldNull() {
        assertNull(ToolDefs.napiPath(null))
        assertNull(ToolDefs.napiPath(""))
        assertNull(ToolDefs.napiPath("   "))
        assertNull(ToolDefs.napiPath("/api"))
        // An absolute URL is not ours — never send the device token there.
        assertNull(ToolDefs.napiPath("https://example.com/api/portal/tasks"))
    }

    @Test
    fun everyShippedSchemaMapsToItsRoles() {
        val defs = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        assertEquals("title", defs.getValue("task").schema.title)
        assertEquals(listOf("due"), defs.getValue("task").schema.meta)
        assertEquals("name", defs.getValue("contacts").schema.title)
        assertEquals(listOf("phone", "email"), defs.getValue("contacts").schema.meta)
        assertEquals("title", defs.getValue("doc").schema.title)
        assertEquals(listOf("category"), defs.getValue("doc").schema.meta)
        assertEquals("name", defs.getValue("photo").schema.title)
        assertEquals(listOf("people"), defs.getValue("photo").schema.meta)
        assertEquals("label", defs.getValue("note").schema.title)
        assertTrue(defs.getValue("note").schema.meta.isEmpty())
        assertTrue(defs.getValue("energy").schema.isEmpty)
        assertTrue(defs.getValue("home").schema.isEmpty)
    }

    /**
     * Which tools the picker may offer: `note` has no `tool-api-path` (nothing to
     * fetch) and `energy`/`home` ship an empty schema (bespoke browser cards) —
     * offering either would pin a permanently blank widget.
     */
    @Test
    fun onlyListableToolsAreOfferable() {
        val defs = ToolDefs.parseCatalog(shippedCatalog)
        assertEquals(
            listOf("task", "contacts", "doc", "photo"),
            defs.filter { it.isListable }.map { it.id },
        )
        val byId = defs.associateBy { it.id }
        assertFalse("no tool-api-path", byId.getValue("note").isListable)
        assertFalse("empty schema", byId.getValue("energy").isListable)
        assertFalse("empty schema", byId.getValue("home").isListable)
    }

    @Test
    fun allRolesOfTheVocabularyAreRead() {
        val body = """
            {"ok":true,"defs":[{"tool-id":"x","tool-label":"X","tool-api-path":"/api/x",
             "tool-actions":["x.do","x.other"],
             "tool-cell-schema":{"title":"name","subtitle":"role","meta":["phone","email"],
               "badge":"state","icon":"glyph","actions":["x.do"]}}]}
        """.trimIndent()
        val s = ToolDefs.parseCatalog(body).single().schema
        assertEquals("name", s.title)
        assertEquals("role", s.subtitle)
        assertEquals(listOf("phone", "email"), s.meta)
        assertEquals("state", s.badge)
        assertEquals("glyph", s.icon)
        assertEquals(listOf("x.do"), s.actions)
    }

    /** `state` is the accepted alias for the `badge` chip role. */
    @Test
    fun stateIsAnAliasForBadge() {
        val body = """{"defs":[{"tool-id":"x","tool-cell-schema":{"title":"t","state":"status"}}]}"""
        assertEquals("status", ToolDefs.parseCatalog(body).single().schema.badge)
    }

    /**
     * Anything outside the closed vocabulary — an unknown role, a role whose value
     * is markup-ish or the wrong JSON type — is skipped, never guessed at.
     */
    @Test
    fun unknownRolesAndWrongTypesDegrade() {
        val body = """
            {"defs":[{"tool-id":"x","tool-label":"X","tool-api-path":"/api/x",
             "tool-cell-schema":{"title":"name","template":"<b>{{name}}</b>",
               "onClick":"doThing()","meta":{"nope":1},"subtitle":42,"badge":{"f":"x"}}}]}
        """.trimIndent()
        val def = ToolDefs.parseCatalog(body).single()
        assertEquals("name", def.schema.title)
        assertNull(def.schema.subtitle)
        assertNull(def.schema.badge)
        assertTrue(def.schema.meta.isEmpty())
        // A title survives → the tool is still usable as a list.
        assertTrue(def.isListable)
    }

    /** A schema may only reference action ids the def actually declares. */
    @Test
    fun undeclaredActionIdsAreDropped() {
        val body = """
            {"defs":[{"tool-id":"x","tool-actions":["x.do"],
             "tool-cell-schema":{"title":"t","actions":["x.do","x.ghost"]}}]}
        """.trimIndent()
        assertEquals(listOf("x.do"), ToolDefs.parseCatalog(body).single().schema.actions)
    }

    @Test
    fun malformedCatalogNeverThrows() {
        assertTrue(ToolDefs.parseCatalog(null).isEmpty())
        assertTrue(ToolDefs.parseCatalog("").isEmpty())
        assertTrue(ToolDefs.parseCatalog("not json").isEmpty())
        assertTrue(ToolDefs.parseCatalog("""{"ok":false}""").isEmpty())
        // A def without a tool-id is skipped; the rest of the catalog survives.
        val mixed = """{"defs":[{"tool-label":"kaputt"},{"tool-id":"ok","tool-label":"OK"}]}"""
        assertEquals(listOf("ok"), ToolDefs.parseCatalog(mixed).map { it.id })
    }

    // --- tool-compose-path (#71, solarisbay#1213) -----------------------------

    /**
     * Which of the shipped tools may be offered an "Erfassen" tile. The five
     * create-capable tools declare the route themselves; `home` and `energy` are
     * view-only and declare none — and a missing declaration must stay missing,
     * because a synthesised `#/p/home/new` would land on the start page.
     */
    @Test
    fun onlyDeclaringToolsCarryAComposePath() {
        val byId = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        assertEquals("#/p/task/new", byId.getValue("task").composePath)
        assertEquals("#/p/note/new", byId.getValue("note").composePath)
        assertEquals("#/p/contacts/new", byId.getValue("contacts").composePath)
        assertEquals("#/p/doc/new", byId.getValue("doc").composePath)
        assertEquals("#/p/photo/new", byId.getValue("photo").composePath)
        assertNull(byId.getValue("home").composePath)
        assertNull(byId.getValue("energy").composePath)
    }

    @Test
    fun composePathIsTakenAsDeclaredAndForeignOnesRefused() {
        assertEquals("#/p/x/new", ToolDefs.composePath("  #/p/x/new  "))
        assertNull(ToolDefs.composePath(null))
        assertNull(ToolDefs.composePath(""))
        assertNull(ToolDefs.composePath("   "))
        // Off-server — never open a tile at someone else's host.
        assertNull(ToolDefs.composePath("https://example.com/#/p/x/new"))
    }

    // --- tool-action-params (#90, solarisbay#1214) ----------------------------

    /** The real `.task` declaration — the only tool that ships one today. */
    @Test
    fun taskActionParamsAreReadPerActionId() {
        val byId = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        val task = byId.getValue("task").actionParams
        assertEquals(setOf("task.set_status", "task.add", "task.update"), task.keys)
        assertEquals(
            mapOf("entity_id" to "\$id", "status" to "done"),
            task["task.set_status"],
        )
        // Every other shipped tool declares none — so no row of theirs gets a button.
        listOf("contacts", "doc", "photo", "note", "home", "energy").forEach {
            assertTrue(it, byId.getValue(it).actionParams.isEmpty())
        }
    }

    /**
     * A source must be a non-empty string; a bare `$` names no field (the server
     * lints exactly that). Such an action is dropped whole rather than sent with a
     * hole in its params.
     */
    @Test
    fun unusableActionParamMappingsAreDropped() {
        val body = """
            {"defs":[{"tool-id":"x","tool-actions":["a","b","c","d"],
             "tool-action-params":{"a":{"p":"${'$'}"},"b":{"p":42},"c":{},
               "d":{"p":"${'$'}field","q":"lit"}}}]}
        """.trimIndent()
        val params = ToolDefs.parseCatalog(body).single().actionParams
        assertEquals(setOf("d"), params.keys)
        assertEquals(mapOf("p" to "\$field", "q" to "lit"), params["d"])
    }

    @Test
    fun missingOrMalformedActionParamsYieldAnEmptyMap() {
        assertTrue(ToolDefs.parseActionParams(null).isEmpty())
        val body = """{"defs":[{"tool-id":"x","tool-action-params":"nope"}]}"""
        assertTrue(ToolDefs.parseCatalog(body).single().actionParams.isEmpty())
    }

    // --- item payloads --------------------------------------------------------

    @Test
    fun rowsAreFoundUnderTheHandlerSpecificKey() {
        assertEquals(2, ToolDefs.rows("""{"ok":true,"tasks":[{"a":1},{"a":2}]}""").size)
        assertEquals(1, ToolDefs.rows("""{"ok":true,"contacts":[{"a":1}]}""").size)
        assertEquals(1, ToolDefs.rows("""{"ok":true,"documents":[{"a":1}]}""").size)
        // A brand-new tool with a key we've never seen still renders.
        assertEquals(3, ToolDefs.rows("""{"ok":true,"widgets":[{},{},{}]}""").size)
        // …and a bare array works too.
        assertEquals(2, ToolDefs.rows("""[{"a":1},{"a":2}]""").size)
    }

    @Test
    fun rowsAreBoundedAndFailSoft() {
        val many = (1..100).joinToString(",", "[", "]") { """{"i":$it}""" }
        assertEquals(ToolDefs.MAX_ROWS, ToolDefs.rows(many).size)
        assertEquals(5, ToolDefs.rows(many, cap = 5).size)
        assertTrue(ToolDefs.rows(null).isEmpty())
        assertTrue(ToolDefs.rows("nope").isEmpty())
        assertTrue(ToolDefs.rows("""{"ok":true}""").isEmpty())
    }

    /** The `/napi/action-callback` wire shape (solarisbay `action_callback`). */
    @Test
    fun actionBodyCarriesActionIdAndParams() {
        val params = org.json.JSONObject().put("entity_id", "task.42").put("status", "done")
        val body = ToolDefs.actionBody("task.set_status", params)
        assertEquals("task.set_status", body.getString("action_id"))
        assertEquals("task.42", body.getJSONObject("params").getString("entity_id"))
        assertFalse(body.has("confirmed"))
        assertTrue(ToolDefs.actionBody("x.do", null, confirmed = true).getBoolean("confirmed"))
    }
}
