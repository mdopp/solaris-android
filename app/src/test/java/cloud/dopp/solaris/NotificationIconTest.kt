package cloud.dopp.solaris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the white-blob regression (#88). Android masks a notification's
 * **small icon** by its alpha channel and paints every opaque pixel white, so a
 * full-colour launcher icon — `mipmap/ic_launcher` is 100 % opaque — renders as a
 * featureless disc in the status bar. Nothing about that fails to compile, and no
 * emulator test catches it, so the rule is asserted over the sources themselves:
 * every `setSmallIcon` must point at the transparent-background vector, and that
 * vector must not grow an opaque backing tile (which would recreate the blob).
 * The figure (#99) is head+body as two subpaths of ONE white path, so the
 * single-fill rule below still holds.
 */
class NotificationIconTest {

    private val mainDir = locate("src/main")

    @Test
    fun noNotificationUsesTheLauncherIcon() {
        val offenders = smallIconCalls().filter { it.contains("mipmap") || it.contains("ic_launcher") }
        assertTrue(
            "setSmallIcon must not use the opaque launcher icon (#88): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun everySmallIconIsTheNotificationVector() {
        val calls = smallIconCalls()
        // If this ever hits 0 the assertion above would pass vacuously.
        assertTrue("expected setSmallIcon calls in app/src/main", calls.isNotEmpty())
        for (call in calls) {
            assertTrue("unexpected small icon: $call", call.contains("R.drawable.ic_notification"))
        }
    }

    @Test
    fun everySmallIconCarriesTheBrandAccent() {
        // The mask strips all colour from the icon; setColor is what paints the
        // Solaris blue back behind it, so the two always travel together.
        val colors = kotlinSources().sumOf { src ->
            src.readLines().count { it.contains(".setColor(NOTIF_ACCENT)") }
        }
        assertEquals("each small icon needs its setColor accent (#88)", smallIconCalls().size, colors)
    }

    @Test
    fun theVectorIsWhiteOnTransparency() {
        val xml = File(mainDir, "res/drawable/ic_notification.xml")
        assertTrue("missing res/drawable/ic_notification.xml", xml.isFile)
        val text = xml.readText()

        val fills = Regex("""android:fillColor="([^"]+)"""").findAll(text)
            .map { it.groupValues[1].uppercase() }
            .toList()
        assertEquals("expected exactly one path — a bare figure, no backing tile", 1, fills.size)
        assertEquals("the small icon may only be white", "#FFFFFF", fills.single())
        assertFalse("a stroked shape would thicken into a blob too", text.contains("strokeColor"))

        // A 24dp viewport is what the status bar samples; edge-touching shapes clip.
        assertTrue("expected a 24 viewport", text.contains("""android:viewportWidth="24""""))
        assertTrue("expected a 24 viewport", text.contains("""android:viewportHeight="24""""))
    }

    private fun smallIconCalls(): List<String> =
        kotlinSources().flatMap { src ->
            src.readLines().filter { it.contains(".setSmallIcon(") }.map { "${src.name}: ${it.trim()}" }
        }

    private fun kotlinSources(): List<File> =
        File(mainDir, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

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
