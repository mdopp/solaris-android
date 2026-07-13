package cloud.dopp.solaris

import cloud.dopp.solaris.widget.WidgetRender
import cloud.dopp.solaris.widget.WidgetRender.Tier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic check for the widget layout tier selection (#20): a wide-but-flat
 * widget (e.g. 4×1) must land on WIDE — which carries inline controls — instead
 * of falling through to SMALL, which has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetSizeTierTest {

    @Test fun tallEnoughIsMedium() {
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(180, 110))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 200))
    }

    @Test fun wideButFlatIsWide() {
        // 4×1 on a typical launcher grid: ~270dp wide, ~40dp tall.
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(270, 40))
        assertEquals(Tier.WIDE, WidgetRender.sizeTier(250, 100))
    }

    @Test fun smallStaysSmall() {
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(110, 40))
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(180, 40)) // wide-ish but < 250
    }

    @Test fun singleCellIsTiny() {
        // 1×1 on a typical launcher grid: ~60–100dp square → TINY (#31).
        assertEquals(Tier.TINY, WidgetRender.sizeTier(70, 70))
        assertEquals(Tier.TINY, WidgetRender.sizeTier(109, 109))
        // Just past either edge climbs back into SMALL.
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(110, 90)) // wide enough
        assertEquals(Tier.SMALL, WidgetRender.sizeTier(90, 110)) // tall enough
    }

    /** #29 reverted: a big/tall box now resolves to MEDIUM (no history chart). */
    @Test fun bigBoxIsMedium() {
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(180, 220))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 300))
        assertEquals(Tier.MEDIUM, WidgetRender.sizeTier(300, 200))
    }

    /** #33: lights/switches toggle on a body tap; covers/sensors open the PWA. */
    @Test fun onlyLightAndSwitchToggleOnBodyTap() {
        assertEquals(true, WidgetRender.togglesOnBodyTap("light"))
        assertEquals(true, WidgetRender.togglesOnBodyTap("switch"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("cover"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("sensor"))
        assertEquals(false, WidgetRender.togglesOnBodyTap("climate"))
        assertEquals(false, WidgetRender.togglesOnBodyTap(""))
    }

    /** #38: only garage/door/gate covers are lock-gated (need a confirm). */
    @Test fun onlySensitiveCoverClassesNeedTheLockBadge() {
        assertEquals(true, cloud.dopp.solaris.data.isSensitiveDevice("cover", "garage"))
        assertEquals(true, cloud.dopp.solaris.data.isSensitiveDevice("cover", "door"))
        assertEquals(true, cloud.dopp.solaris.data.isSensitiveDevice("cover", "gate"))
        // Ordinary covers + other domains are not gated.
        assertEquals(false, cloud.dopp.solaris.data.isSensitiveDevice("cover", "shade"))
        assertEquals(false, cloud.dopp.solaris.data.isSensitiveDevice("cover", null))
        assertEquals(false, cloud.dopp.solaris.data.isSensitiveDevice("light", "garage"))
        assertEquals(false, cloud.dopp.solaris.data.isSensitiveDevice(null, "garage"))
    }
}
