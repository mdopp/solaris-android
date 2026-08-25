package cloud.dopp.solaris

import cloud.dopp.solaris.data.ToolDefs
import cloud.dopp.solaris.widget.ToolLaunch
import cloud.dopp.solaris.widget.ToolLaunchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which 1×1 launcher tiles the catalog yields (#71). The rule the whole ticket
 * turns on: a **chat** tile for every tool, an **Erfassen** tile only where the def
 * declares `tool-compose-path` — so the view-only `home`/`energy` never get a
 * create button that would quietly land on `#/p/start`.
 *
 * Fixture = the real shipped defs (`templates/solaris/skills/household/…`), so a
 * changed server declaration shows up here first.
 *
 * Robolectric only for `org.json`; the mapper itself is Android-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolLaunchTest {

    private val shippedCatalog = """
        {"ok": true, "kind": "tool", "defs": [
          {"tool-id":"task","tool-label":"Aufgabe","tool-api-path":"/api/portal/tasks?done=1",
           "tool-compose-path":"#/p/task/new"},
          {"tool-id":"contacts","tool-label":"Kontakt","tool-api-path":"/api/portal/persons",
           "tool-compose-path":"#/p/contacts/new"},
          {"tool-id":"doc","tool-label":"Dokument","tool-compose-path":"#/p/doc/new"},
          {"tool-id":"photo","tool-label":"Foto","tool-compose-path":"#/p/photo/new"},
          {"tool-id":"note","tool-label":"Notiz","tool-compose-path":"#/p/note/new"},
          {"tool-id":"energy","tool-label":"Energie","tool-api-path":"/api/portal/energy"},
          {"tool-id":"home","tool-label":"Gerät","tool-api-path":"/api/portal/start/addable"}
        ]}
    """.trimIndent()

    private fun tiles() = ToolLaunch.tiles(ToolDefs.parseCatalog(shippedCatalog))

    @Test
    fun everyToolIsOfferedAChatTile() {
        val chat = tiles().filter { it.mode == ToolLaunchMode.CHAT }
        assertEquals(
            listOf("task", "contacts", "doc", "photo", "note", "energy", "home"),
            chat.map { it.toolId },
        )
        assertEquals("/#/?tool=task", chat.first().path)
        assertEquals("Energie", chat.first { it.toolId == "energy" }.label)
    }

    @Test
    fun onlyDeclaringToolsAreOfferedAComposeTile() {
        val compose = tiles().filter { it.mode == ToolLaunchMode.COMPOSE }
        assertEquals(
            listOf("task", "contacts", "doc", "photo", "note"),
            compose.map { it.toolId },
        )
        assertEquals("/#/p/task/new", compose.first().path)
    }

    /** The regression this ticket exists to prevent: no invented create route. */
    @Test
    fun aViewOnlyToolGetsNoComposeTile() {
        val byId = ToolDefs.parseCatalog(shippedCatalog).associateBy { it.id }
        assertNull(ToolLaunch.path(byId.getValue("home"), ToolLaunchMode.COMPOSE))
        assertNull(ToolLaunch.path(byId.getValue("energy"), ToolLaunchMode.COMPOSE))
        assertTrue(
            tiles().none { it.toolId in setOf("home", "energy") && it.mode == ToolLaunchMode.COMPOSE },
        )
    }

    /** A `.tool` this build never heard of is offered too — that's the point. */
    @Test
    fun anUnknownToolIsOfferedFromTheCatalogAlone() {
        val defs = ToolDefs.parseCatalog(
            """{"defs":[{"tool-id":"garden","tool-label":"Garten",
                "tool-compose-path":"#/p/garden/new"}]}""",
        )
        assertEquals(
            listOf("/#/p/garden/new", "/#/?tool=garden"),
            ToolLaunch.tiles(defs).map { it.path },
        )
    }

    @Test
    fun aDefWithoutAnIdYieldsNoTile() {
        assertTrue(ToolLaunch.tiles(ToolDefs.parseCatalog("""{"defs":[{"tool-label":"X"}]}""")).isEmpty())
        assertTrue(ToolLaunch.tiles(emptyList()).isEmpty())
    }

    /** A bound tile must survive an app update — the persisted key is the contract. */
    @Test
    fun modeKeysRoundTripAndUnknownFallsBackToChat() {
        ToolLaunchMode.entries.forEach {
            assertEquals(it, ToolLaunchMode.fromKey(it.key))
        }
        assertEquals(ToolLaunchMode.CHAT, ToolLaunchMode.fromKey(null))
        assertEquals(ToolLaunchMode.CHAT, ToolLaunchMode.fromKey("nonsense"))
    }
}
