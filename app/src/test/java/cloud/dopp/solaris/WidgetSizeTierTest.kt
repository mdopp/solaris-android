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
}
