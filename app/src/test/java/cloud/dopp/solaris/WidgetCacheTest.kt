package cloud.dopp.solaris

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.dopp.solaris.data.Card
import cloud.dopp.solaris.data.SbHome
import cloud.dopp.solaris.widget.WidgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip + stale-then-fresh checks for the last-good render cache (#46): a
 * failed fetch re-renders from here, so encode→decode must preserve the fields
 * each widget shows, and a never-loaded widget must read back null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetCacheTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun cardRoundTripPreservesFields() {
        val card = Card(
            entityId = "light.wohnzimmer", name = "Wohnzimmer", domain = "light",
            deviceClass = null, state = "on", unit = null, brightness = 128,
            updatedAtMs = 1710000000000L,
        )
        WidgetCache.putCard(ctx, 7, card)
        val back = WidgetCache.getCard(ctx, 7)!!
        assertEquals("light.wohnzimmer", back.entityId)
        assertEquals("Wohnzimmer", back.name)
        assertEquals("light", back.domain)
        assertEquals("on", back.state)
        assertEquals(128, back.brightness)
        assertEquals(1710000000000L, back.updatedAtMs)
        // Absent optionals must NOT be resurrected as non-null.
        assertNull(back.deviceClass)
        assertNull(back.unit)
        assertNull(back.position)
        assertNull(back.temperature)
        // …including the colour ones (#87): a white bulb stays a white bulb.
        assertNull(back.rgbColor)
        assertNull(back.colorMode)
        assertNull(back.colorTemp)
        assertEquals(emptyList<String>(), back.supportedColorModes)
    }

    /** #87: the stale render must keep the swatch, not fall back to plain −/+. */
    @Test fun colourCardKeepsItsColour() {
        WidgetCache.putCard(
            ctx, 11,
            Card(
                entityId = "light.esszimmer", name = "Esszimmer", domain = "light",
                deviceClass = null, state = "on", unit = null, brightness = 200,
                rgbColor = 0xFFA020, supportedColorModes = listOf("rgb", "color_temp"),
                colorMode = "rgb", colorTemp = 366,
            ),
        )
        val back = WidgetCache.getCard(ctx, 11)!!
        assertEquals(0xFFA020, back.rgbColor)
        assertEquals(listOf("rgb", "color_temp"), back.supportedColorModes)
        assertEquals("rgb", back.colorMode)
        assertEquals(366, back.colorTemp)
        assertEquals(true, back.isColorCapable)
    }

    @Test fun coverCardKeepsPositionAndClass() {
        WidgetCache.putCard(
            ctx, 8,
            Card(
                entityId = "cover.garage", name = "Garage", domain = "cover",
                deviceClass = "garage", state = "open", unit = null, position = 40,
            ),
        )
        val back = WidgetCache.getCard(ctx, 8)!!
        assertEquals("garage", back.deviceClass)
        assertEquals(40, back.position)
        assertNull(back.brightness)
    }

    @Test fun homeRoundTrip() {
        WidgetCache.putHome(ctx, 3, SbHome(5, 1, 0, 2, 4))
        val back = WidgetCache.getHome(ctx, 3)!!
        assertEquals(5, back.servicesUp)
        assertEquals(1, back.servicesFailed)
        assertEquals(2, back.pendingApprovals)
        assertEquals(4, back.pendingUpdates)
    }

    @Test fun serviceCountsRoundTrip() {
        WidgetCache.putServiceCounts(ctx, 9, Triple(6, 2, 1))
        assertEquals(Triple(6, 2, 1), WidgetCache.getServiceCounts(ctx, 9))
    }

    @Test fun neverLoadedReadsNull() {
        assertNull(WidgetCache.getCard(ctx, 999))
        assertNull(WidgetCache.getHome(ctx, 999))
        assertNull(WidgetCache.getServiceCounts(ctx, 999))
    }

    @Test fun clearDropsEveryPayloadForId() {
        WidgetCache.putCard(
            ctx, 11,
            Card("switch.x", "X", "switch", null, "on", null),
        )
        WidgetCache.putHome(ctx, 11, SbHome(1, 0, 0, 0, 0))
        WidgetCache.putServiceCounts(ctx, 11, Triple(1, 0, 0))
        WidgetCache.clear(ctx, 11)
        assertNull(WidgetCache.getCard(ctx, 11))
        assertNull(WidgetCache.getHome(ctx, 11))
        assertNull(WidgetCache.getServiceCounts(ctx, 11))
    }
}
