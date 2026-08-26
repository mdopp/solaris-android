package cloud.dopp.solaris.widget

import androidx.annotation.DrawableRes
import cloud.dopp.solaris.R

/**
 * HA-domain → **shortcut** icon (#99). A sibling of [DeviceIcons], not a
 * replacement: the two answer different questions.
 *
 * [DeviceIcons] hands out the widget glyphs, which are white on transparency
 * because a widget draws them on its own dark tile and tints them there. The
 * launcher does the opposite — it puts a shortcut icon on *its* background and,
 * for an icon that isn't adaptive, wraps it in a light disc first. A white glyph
 * on transparency comes out of that as a plain white dot, which is exactly what
 * the menu showed.
 *
 * So a shortcut icon brings its own field: `ic_shortcut_*` is an adaptive icon,
 * white glyph on opaque Solaris blue, and therefore reads on a light launcher
 * background as well as a dark one. Pure → JVM-testable ([ShortcutIconTest]).
 */
object ShortcutIcons {
    @DrawableRes
    fun forDomain(domain: String?): Int = when (domain) {
        "light" -> R.drawable.ic_shortcut_light
        "switch" -> R.drawable.ic_shortcut_switch
        "cover" -> R.drawable.ic_shortcut_cover
        "climate" -> R.drawable.ic_shortcut_climate
        "lock" -> R.drawable.ic_shortcut_lock
        else -> R.drawable.ic_shortcut_device
    }
}
