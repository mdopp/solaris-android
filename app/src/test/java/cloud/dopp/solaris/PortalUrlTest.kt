package cloud.dopp.solaris

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure coverage for the request-access portal derivation (#50): the CTA lives on
 * the ServiceBay portal at the parent (apex) domain, so the paired Solaris host's
 * leftmost subdomain label is stripped (chat.dopp.cloud → dopp.cloud), never
 * `<solaris>/portal` (which 404s). Scheme defaults to https; port/path are dropped.
 */
class PortalUrlTest {

    @Test fun stripsSubdomainToApex() {
        assertEquals("https://dopp.cloud", SolarisConfig.portalUrl("https://chat.dopp.cloud"))
    }

    @Test fun defaultsSchemeWhenMissing() {
        assertEquals("https://dopp.cloud", SolarisConfig.portalUrl("chat.dopp.cloud"))
    }

    @Test fun dropsTrailingSlashPortAndPath() {
        assertEquals("https://dopp.cloud", SolarisConfig.portalUrl("https://chat.dopp.cloud:8787/portal/"))
    }

    @Test fun apexHostUnchanged() {
        assertEquals("https://dopp.cloud", SolarisConfig.portalUrl("https://dopp.cloud"))
    }

    @Test fun deeperSubdomainStripsOnlyLeftmost() {
        // chat.home.dopp.cloud → home.dopp.cloud (only the leftmost label is dropped).
        assertEquals("https://home.dopp.cloud", SolarisConfig.portalUrl("https://chat.home.dopp.cloud"))
    }

    @Test fun preservesHttpScheme() {
        assertEquals("http://dopp.cloud", SolarisConfig.portalUrl("http://chat.dopp.cloud"))
    }
}
