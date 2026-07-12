package cloud.dopp.solaris

import cloud.dopp.solaris.data.Card
import cloud.dopp.solaris.widget.ActiveDevicesRender
import cloud.dopp.solaris.widget.ActiveDevicesRender.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic checks for the active-devices overview widget (#28): tier
 * selection and the German per-domain state label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveDevicesRenderTest {

    @Test fun tallWideIsLarge() {
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(180, 180))
        assertEquals(Tier.LARGE, ActiveDevicesRender.sizeTier(300, 300))
    }

    @Test fun smallOrFlatIsSmall() {
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(110, 40))
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(300, 100))
        assertEquals(Tier.SMALL, ActiveDevicesRender.sizeTier(120, 300))
    }

    private fun card(domain: String, state: String, brightness: Int? = null, position: Int? = null) =
        Card(
            entityId = "$domain.x", name = "X", domain = domain, deviceClass = null,
            state = state, unit = null, brightness = brightness, position = position,
        )

    /** #28: the number of shown rows scales with the widget's min height. */
    @Test fun rowsScaleWithHeight() {
        // A short large box fits only a couple of rows.
        assertEquals(2, ActiveDevicesRender.rowsToShow(minH = 120, count = 10))
        // A tall box fits more, clamped to the concrete slot ceiling.
        assertEquals(ActiveDevicesRender.MAX_ROWS, ActiveDevicesRender.rowsToShow(minH = 600, count = 20))
        // Never more rows than there are active devices (no empty trailing slots).
        assertEquals(3, ActiveDevicesRender.rowsToShow(minH = 600, count = 3))
        // Degenerate/zero height still yields at least one row.
        assertEquals(1, ActiveDevicesRender.rowsToShow(minH = 0, count = 5))
    }

    @Test fun stateLabels() {
        assertEquals("an", ActiveDevicesRender.stateLabel(card("light", "on")))
        assertEquals("an · 50 %", ActiveDevicesRender.stateLabel(card("light", "on", brightness = 128)))
        assertEquals("an", ActiveDevicesRender.stateLabel(card("switch", "on")))
        assertEquals("offen", ActiveDevicesRender.stateLabel(card("cover", "open", position = 100)))
        assertEquals("40 % offen", ActiveDevicesRender.stateLabel(card("cover", "open", position = 40)))
    }
}
