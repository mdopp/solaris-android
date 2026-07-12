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
}
