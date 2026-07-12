package cloud.dopp.solaris

import cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider
import cloud.dopp.solaris.widget.CameraWidgetProvider
import cloud.dopp.solaris.widget.DeviceWidgetProvider
import cloud.dopp.solaris.widget.EnergyStatsWidgetProvider
import cloud.dopp.solaris.widget.EnergyTotalsWidgetProvider
import cloud.dopp.solaris.widget.EnergyWidgetProvider
import cloud.dopp.solaris.widget.VoiceWidgetProvider
import cloud.dopp.solaris.widget.WidgetTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget-type chooser table (#34) must list *every* pinnable provider, so the
 * onboarding "Widget hinzufügen" dialog can offer all types — not only the device
 * widget. Pure JVM coverage: no Android context needed.
 */
class WidgetTypesTest {

    @Test
    fun tableCoversEveryWidgetProvider() {
        val expected = setOf<Class<*>>(
            DeviceWidgetProvider::class.java,
            EnergyWidgetProvider::class.java,
            EnergyStatsWidgetProvider::class.java,
            EnergyTotalsWidgetProvider::class.java,
            ActiveDevicesWidgetProvider::class.java,
            CameraWidgetProvider::class.java,
            VoiceWidgetProvider::class.java,
        )
        assertEquals(expected, WidgetTypes.ALL.map { it.provider }.toSet())
    }

    @Test
    fun deviceWidgetIsListedFirst() {
        assertEquals(DeviceWidgetProvider::class.java, WidgetTypes.ALL.first().provider)
    }

    @Test
    fun everyEntryHasLabelAndIcon() {
        assertTrue(WidgetTypes.ALL.isNotEmpty())
        WidgetTypes.ALL.forEach {
            assertTrue("label res id set", it.label != 0)
            assertTrue("icon res id set", it.icon != 0)
        }
    }

    @Test
    fun noDuplicateProviders() {
        val providers = WidgetTypes.ALL.map { it.provider }
        assertEquals(providers.size, providers.toSet().size)
    }
}
