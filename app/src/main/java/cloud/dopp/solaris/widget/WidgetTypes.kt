package cloud.dopp.solaris.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.solaris.R

/**
 * The typed table of pinnable widget types (#34). One entry per home-screen
 * widget provider, so the onboarding "Widget hinzufügen" chooser can list *every*
 * type — not only the device widget — and pin the picked provider via
 * `requestPinAppWidget`.
 *
 * Pure (label/icon are resource ids, provider is a class reference) → the table
 * itself is unit-testable on the JVM without an Android context.
 */
object WidgetTypes {

    data class Entry(
        val provider: Class<*>,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
    )

    /** Ordered as shown in the chooser: device first, then energy, then the rest. */
    val ALL: List<Entry> = listOf(
        Entry(DeviceWidgetProvider::class.java, R.string.widget_type_device, R.drawable.ic_device),
        Entry(EnergyWidgetProvider::class.java, R.string.widget_type_energy_now, R.drawable.ic_bolt),
        Entry(EnergyStatsWidgetProvider::class.java, R.string.widget_type_energy_history, R.drawable.ic_bolt),
        Entry(EnergyTotalsWidgetProvider::class.java, R.string.widget_type_energy_totals, R.drawable.ic_bolt),
        Entry(ActiveDevicesWidgetProvider::class.java, R.string.widget_type_active, R.drawable.ic_switch),
        Entry(CameraWidgetProvider::class.java, R.string.widget_type_camera, R.drawable.ic_camera),
        Entry(VoiceWidgetProvider::class.java, R.string.widget_type_voice, R.drawable.ic_mic),
    )
}
