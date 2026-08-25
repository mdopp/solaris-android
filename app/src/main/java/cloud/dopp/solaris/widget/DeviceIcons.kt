package cloud.dopp.solaris.widget

import androidx.annotation.DrawableRes
import cloud.dopp.solaris.R

/**
 * Shared HA-domain → type-icon mapping used by both the widget renderer and the
 * device-picker row, so a light/switch/cover/climate reads the same everywhere.
 * Pure (no Android context) → unit-testable on the JVM.
 */
object DeviceIcons {
    @DrawableRes
    fun forDomain(domain: String?): Int = when (domain) {
        "light" -> R.drawable.ic_light
        "switch" -> R.drawable.ic_switch
        "cover" -> R.drawable.ic_cover
        "climate" -> R.drawable.ic_climate
        // A door with a keyhole (#84) — the padlock ic_lock stays the confirm badge.
        "lock" -> R.drawable.ic_door_lock
        else -> R.drawable.ic_device
    }
}
