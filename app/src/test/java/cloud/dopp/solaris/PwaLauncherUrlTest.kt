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
}
