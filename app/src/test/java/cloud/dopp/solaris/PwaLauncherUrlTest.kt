package cloud.dopp.solaris

import cloud.dopp.solaris.widget.PwaLauncher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the PWA URL builder (#27) — the seam-collapsing join
 * extracted from `PwaLauncher.open()` so a trailing slash on the base and a
 * leading slash on the route never produce a double slash.
 */
class PwaLauncherUrlTest {

    @Test
    fun trailingSlashBaseAndLeadingSlashPath_noDoubleSlash() {
        assertEquals("https://chat.dopp.cloud/#/p/energy", PwaLauncher.url("https://chat.dopp.cloud/", "/#/p/energy"))
    }

    @Test
    fun baseWithoutTrailingSlash_exactlyOneSlash() {
        assertEquals("https://chat.dopp.cloud/#/p/energy", PwaLauncher.url("https://chat.dopp.cloud", "/#/p/energy"))
    }

    @Test
    fun energyRouteProducesExpectedUrl() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/energy",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.ENERGY),
        )
    }

    @Test
    fun deviceRouteEmbedsEntityId() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/device/light.kitchen",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.device("light.kitchen")),
        )
    }

    @Test
    fun deviceRouteWithTrailingSlashBase_noDoubleSlash() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/device/cover.garage",
            PwaLauncher.url("https://chat.dopp.cloud/", PwaLauncher.Routes.device("cover.garage")),
        )
    }

    @Test
    fun rootRouteProducesExpectedUrl() {
        assertEquals(
            "https://chat.dopp.cloud/",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.ROOT),
        )
    }

    @Test
    fun trailingSlashBasePlusRoot_singleSlash() {
        assertEquals("https://chat.dopp.cloud/", PwaLauncher.url("https://chat.dopp.cloud/", PwaLauncher.Routes.ROOT))
    }

    // --- Camera widget route (#36) ---

    @Test
    fun cameraRouteEmbedsEntityId() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/camera/camera.hof",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.camera("camera.hof")),
        )
    }

    @Test
    fun cameraRouteWithTrailingSlashBase_noDoubleSlash() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/camera/camera.garage",
            PwaLauncher.url("https://chat.dopp.cloud/", PwaLauncher.Routes.camera("camera.garage")),
        )
    }

    @Test
    fun cameraRootRouteProducesExpectedUrl() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/camera",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.CAMERA_ROOT),
        )
    }

    // --- Voice widget ?ask= route (#17) ---

    @Test
    fun askRoute_encodesSpacesAsPlus() {
        assertEquals("/#/?ask=Licht+im+Wohnzimmer+an", PwaLauncher.Routes.ask("Licht im Wohnzimmer an"))
    }

    @Test
    fun askRoute_encodesUmlauts() {
        // ä→%C3%A4, ö→%C3%B6, ü→%C3%BC, ß→%C3%9F (UTF-8, percent-encoded).
        assertEquals(
            "/#/?ask=W%C3%A4rme+f%C3%BCr+die+Gr%C3%BC%C3%9Fe",
            PwaLauncher.Routes.ask("Wärme für die Grüße"),
        )
    }

    @Test
    fun askRoute_encodesReservedChars() {
        // '?' and '&' must be encoded so they don't break the hash query.
        assertEquals("/#/?ask=Wie+warm+ist+es%3F", PwaLauncher.Routes.ask("Wie warm ist es?"))
    }

    @Test
    fun askRoute_joinsWithBaseUrl() {
        assertEquals(
            "https://chat.dopp.cloud/#/?ask=Hallo",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.ask("Hallo")),
        )
    }

    // --- Tool launcher tile routes (#71, solarisbay#1213) ---

    /**
     * The compose route is the def's own `tool-compose-path` (`#/p/task/new`) —
     * only the join's leading slash is added here. Never rebuilt from the id: a
     * tool that declares none has no create path at all.
     */
    @Test
    fun composeRouteIsTheDeclaredPathJoinedToTheBase() {
        assertEquals("/#/p/task/new", PwaLauncher.Routes.toolCompose("#/p/task/new"))
        assertEquals(
            "https://chat.dopp.cloud/#/p/note/new",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.toolCompose("#/p/note/new")),
        )
        assertEquals(
            "https://chat.dopp.cloud/#/p/contacts/new",
            PwaLauncher.url("https://chat.dopp.cloud/", PwaLauncher.Routes.toolCompose("#/p/contacts/new")),
        )
        // An already-rooted declaration is not double-slashed.
        assertEquals("/#/p/doc/new", PwaLauncher.Routes.toolCompose("/#/p/doc/new"))
    }

    /** Chat-with-card: `#/?tool=<id>`, consumed once by the PWA. */
    @Test
    fun toolChatRouteEmbedsTheToolId() {
        assertEquals("/#/?tool=task", PwaLauncher.Routes.toolChat("task"))
        assertEquals(
            "https://chat.dopp.cloud/#/?tool=energy",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.toolChat("energy")),
        )
    }

    @Test
    fun toolChatRouteEncodesAnAwkwardId() {
        assertEquals("/#/?tool=my+tool%26x", PwaLauncher.Routes.toolChat("my tool&x"))
    }

    /** The dead-end guard both routes fall back to server-side. */
    @Test
    fun toolFallbackIsTheStartPage() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/start",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.TOOL_START),
        )
    }

    // --- ServiceBay approval verdict route (#43, solarisbay#1085) ---

    @Test
    fun approvalRouteEmbedsIdInHashForm() {
        // The hash is mandatory: server /p/{type} matches one segment, so the
        // plain /p/servicebay/approvals/<id> 404s — only the hash form resolves.
        assertEquals(
            "https://chat.dopp.cloud/#/p/servicebay/approvals/e813e295-9c5f-4c83-a812-d1e14675332a",
            PwaLauncher.url(
                "https://chat.dopp.cloud",
                PwaLauncher.Routes.approval("e813e295-9c5f-4c83-a812-d1e14675332a"),
            ),
        )
    }

    @Test
    fun approvalRouteWithTrailingSlashBase_noDoubleSlash() {
        assertEquals(
            "https://chat.dopp.cloud/#/p/servicebay/approvals/abc123",
            PwaLauncher.url("https://chat.dopp.cloud/", PwaLauncher.Routes.approval("abc123")),
        )
    }
}
