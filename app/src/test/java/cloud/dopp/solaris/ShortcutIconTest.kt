package cloud.dopp.solaris

import cloud.dopp.solaris.widget.DeviceIcons
import cloud.dopp.solaris.widget.ShortcutIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.hypot

/**
 * Guard for the white-dot regression (#99), a sibling of [NotificationIconTest]:
 * both watch an icon built for one masking context being reused in another.
 *
 * The widget glyphs are white on transparency — a widget draws them on its own
 * dark tile and tints them there. A launcher does the reverse: it puts a shortcut
 * icon on *its* background and, when the icon is not adaptive, wraps it in a
 * light disc first. Handing it a white glyph on transparency therefore yields a
 * featureless white dot, which is what the app-icon menu showed.
 *
 * None of that fails to compile and no emulator test catches it, so the rule is
 * asserted over the sources: shortcut icons come from [ShortcutIcons], every one
 * of them is an adaptive icon carrying its own opaque field, and its glyph stays
 * inside the zone the mask cannot clip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutIconTest {

    private val mainDir = locate("src/main")
    private val drawableDir = File(mainDir, "res/drawable")
    private val adaptiveDir = File(mainDir, "res/drawable-v26")

    /** Shortcut name → the widget glyph it mirrors. */
    private val glyphs = mapOf(
        "light" to "ic_light",
        "switch" to "ic_switch",
        "cover" to "ic_cover",
        "climate" to "ic_climate",
        "lock" to "ic_door_lock",
        "device" to "ic_device",
    )

    // --- the mapping ----------------------------------------------------------

    @Test fun everyDomainGetsItsOwnShortcutIcon() {
        assertEquals(R.drawable.ic_shortcut_light, ShortcutIcons.forDomain("light"))
        assertEquals(R.drawable.ic_shortcut_switch, ShortcutIcons.forDomain("switch"))
        assertEquals(R.drawable.ic_shortcut_cover, ShortcutIcons.forDomain("cover"))
        assertEquals(R.drawable.ic_shortcut_climate, ShortcutIcons.forDomain("climate"))
        assertEquals(R.drawable.ic_shortcut_lock, ShortcutIcons.forDomain("lock"))
    }

    /** A domain nobody thought of still gets a picture, not a blank. */
    @Test fun anUnknownDomainFallsBackToTheGenericDevice() {
        for (domain in listOf(null, "", "vacuum", "media_player", "LIGHT")) {
            assertEquals(
                "unexpected fallback for $domain",
                R.drawable.ic_shortcut_device,
                ShortcutIcons.forDomain(domain),
            )
        }
    }

    /** The five domains plus the fallback are six different pictures. */
    @Test fun theIconsAreDistinct() {
        val ids = (glyphs.keys + "vacuum").map { ShortcutIcons.forDomain(it) }.toSet()
        assertEquals(6, ids.size)
    }

    /** The core rule: no shortcut may borrow a widget glyph. */
    @Test fun noShortcutIconIsAWidgetGlyph() {
        val widget = listOf("light", "switch", "cover", "climate", "lock", null)
            .map { DeviceIcons.forDomain(it) }
            .toSet()
        for (domain in listOf("light", "switch", "cover", "climate", "lock", null, "vacuum")) {
            assertFalse(
                "the shortcut for $domain is a white-on-transparent widget glyph (#99)",
                ShortcutIcons.forDomain(domain) in widget,
            )
        }
    }

    // --- what the call site is allowed to do ----------------------------------

    @Test fun everyShortcutIconComesFromShortcutIcons() {
        val calls = setIconCalls()
        // If this ever hits 0 the loop below would pass vacuously.
        assertTrue("expected setIcon calls in app/src/main", calls.isNotEmpty())
        for (call in calls) {
            assertTrue("shortcut icon not from ShortcutIcons (#99): $call", call.contains("ShortcutIcons."))
            assertFalse("shortcut icon from the widget glyphs (#99): $call", call.contains("DeviceIcons."))
        }
    }

    // --- what the drawables have to be ----------------------------------------

    /** Adaptive, so the launcher masks our field instead of wrapping a bare glyph. */
    @Test fun everyShortcutIconIsAdaptiveOverAnOpaqueField() {
        val files = adaptiveIcons()
        assertEquals("one adaptive icon per shortcut", glyphs.size, files.size)
        for (f in files) {
            val text = f.readText()
            assertTrue("${f.name} is not an <adaptive-icon>", text.contains("<adaptive-icon"))
            assertTrue(
                "${f.name} must sit on the opaque shortcut field",
                text.contains("""android:drawable="@color/shortcut_icon_background""""),
            )
            assertTrue(
                "${f.name} must draw its glyph as the foreground layer",
                text.contains("""android:drawable="@drawable/ic_shortcut_fg_"""),
            )
        }
    }

    /** The field is a colour, and an opaque one — a translucent field is the bug. */
    @Test fun theFieldIsFullyOpaque() {
        val colors = File(mainDir, "res/values/colors.xml").readText()
        var value = Regex("""<color name="shortcut_icon_background">([^<]+)</color>""")
            .find(colors)?.groupValues?.get(1)?.trim()
        assertTrue("missing @color/shortcut_icon_background", value != null)
        // It may be an alias of the launcher-icon blue; follow one hop.
        if (value!!.startsWith("@color/")) {
            val target = value.removePrefix("@color/")
            value = Regex("""<color name="$target">([^<]+)</color>""")
                .find(colors)?.groupValues?.get(1)?.trim()
            assertTrue("dangling alias @color/$target", value != null)
        }
        val hex = value!!.removePrefix("#")
        assertTrue("the shortcut field must be a literal colour, got $value", hex.length in setOf(6, 8))
        if (hex.length == 8) {
            assertEquals("the shortcut field must be opaque (#99)", "FF", hex.take(2).uppercase())
        }
    }

    /** Pre-adaptive launchers get the same opaque disc, not the naked glyph. */
    @Test fun theLegacyVariantCarriesTheFieldToo() {
        for (name in glyphs.keys) {
            val f = File(drawableDir, "ic_shortcut_$name.xml")
            assertTrue("missing res/drawable/${f.name}", f.isFile)
            val text = f.readText()
            assertTrue("${f.name} must stack the glyph on a field", text.contains("<layer-list"))
            assertTrue(
                "${f.name} must sit on @drawable/shortcut_disc",
                text.contains("""android:drawable="@drawable/shortcut_disc""""),
            )
            assertTrue(
                "${f.name} must draw the glyph on top",
                text.contains("""android:drawable="@drawable/ic_shortcut_fg_$name""""),
            )
        }
        val disc = File(drawableDir, "shortcut_disc.xml")
        assertTrue("missing res/drawable/shortcut_disc.xml", disc.isFile)
        assertTrue(
            "the disc must be filled with the shortcut field",
            disc.readText().contains("""android:color="@color/shortcut_icon_background""""),
        )
    }

    /**
     * The mask keeps only the inner 66 of the 108 viewport — a radius of 33 around
     * the centre. Every glyph sits in one scaled group, so the whole 24-unit box
     * has to fit inside that circle, corners included.
     */
    @Test fun noGlyphReachesIntoTheClippedBorder() {
        for (name in glyphs.keys) {
            val f = File(drawableDir, "ic_shortcut_fg_$name.xml")
            assertTrue("missing res/drawable/${f.name}", f.isFile)
            val text = f.readText()
            assertTrue("${f.name} needs a 108 viewport", text.contains("""android:viewportWidth="108""""))
            assertTrue("${f.name} needs a 108 viewport", text.contains("""android:viewportHeight="108""""))
            assertEquals("${f.name}: every path belongs to the one scaled group", 1, count(text, "<group"))

            val scaleX = attr(text, "scaleX")
            val scaleY = attr(text, "scaleY")
            val transX = attr(text, "translateX")
            val transY = attr(text, "translateY")
            assertEquals("${f.name}: the glyph must not be distorted", scaleX, scaleY, 0.0001)

            // The source glyph is a 24-unit box, so it maps to [t, t + 24 * s].
            val half = 12 * scaleX
            assertEquals("${f.name}: glyph off-centre horizontally", 54.0, transX + half, 0.05)
            assertEquals("${f.name}: glyph off-centre vertically", 54.0, transY + half, 0.05)
            assertTrue(
                "${f.name}: the glyph corners leave the mask-safe circle",
                hypot(half, half) <= 33.0,
            )
            // Big enough to still read as a picture at a shortcut's size.
            assertTrue("${f.name}: the glyph is too small to recognise", half * 2 >= 40.0)
        }
    }

    /** The shortcut glyph is the widget glyph — same drawing, different field. */
    @Test fun theShortcutGlyphMatchesItsWidgetSibling() {
        for ((name, source) in glyphs) {
            val widget = File(drawableDir, "$source.xml")
            assertTrue("missing res/drawable/${widget.name}", widget.isFile)
            assertTrue(
                "${widget.name} is expected to be a 24-unit glyph",
                widget.readText().contains("""android:viewportWidth="24""""),
            )
            assertEquals(
                "ic_shortcut_fg_$name has drifted from $source",
                paths(widget),
                paths(File(drawableDir, "ic_shortcut_fg_$name.xml")),
            )
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun adaptiveIcons(): List<File> =
        (adaptiveDir.listFiles() ?: emptyArray()).filter { it.name.startsWith("ic_shortcut_") }

    private fun paths(f: File): List<String> =
        Regex("""android:pathData="([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }.toList()

    private fun attr(text: String, name: String): Double =
        Regex("""android:$name="([-0-9.]+)"""").find(text)?.groupValues?.get(1)?.toDouble()
            ?: throw AssertionError("no android:$name")

    private fun count(text: String, needle: String): Int = text.split(needle).size - 1

    private fun setIconCalls(): List<String> =
        File(mainDir, "java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { src ->
                src.readLines()
                    .filter { it.contains(".setIcon(IconCompat") }
                    .map { "${src.name}: ${it.trim()}" }
            }
            .toList()

    /** Unit tests run from the module dir, but don't depend on it. */
    private fun locate(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, rel), File(dir, "app/$rel"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $rel from ${File("").absolutePath}")
    }
}
