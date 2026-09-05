package cloud.dopp.solaris

import cloud.dopp.solaris.realtime.NoticeNotifier
import cloud.dopp.solaris.realtime.RealtimeProtocol
import cloud.dopp.solaris.widget.PwaLauncher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a notice's tap goes (#143).
 *
 * The server announces a new app version as an ordinary household notice carrying
 * `kind: "app-update"` — and **no address**. That is the point: a notification that
 * opens a URL handed to it is a phishing surface, so the notice is only the
 * trigger, and the app resolves the target from the server it is paired with
 * (contract solarisbay#1326).
 *
 * These pin both halves: the one closed value diverts the tap, and everything else
 * — including anything that merely looks like it — does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeKindTest {

    private fun notice(json: String) = RealtimeProtocol.parseHa(json)!!

    private fun route(kind: String?): String {
        val o = JSONObject().put("title", "Solaris").put("body", "Version 2.39.0 liegt bereit")
        if (kind != null) o.put("kind", kind)
        return NoticeNotifier.tapRoute(notice(o.toString()))
    }

    @Test
    fun `an app-update notice opens the download`() {
        assertEquals(PwaLauncher.Routes.DOWNLOAD, route("app-update"))
    }

    /** Case is normalised on parse, so the server's spelling can't break it. */
    @Test
    fun `the discriminator is case-insensitive`() {
        assertEquals(PwaLauncher.Routes.DOWNLOAD, route("App-Update"))
        assertEquals(PwaLauncher.Routes.DOWNLOAD, route("  APP-UPDATE  "))
    }

    /** Every ordinary notice keeps opening Solaris — the pre-#143 behaviour. */
    @Test
    fun `anything else still opens Solaris`() {
        assertEquals(PwaLauncher.Routes.ROOT, route(null))
        assertEquals(PwaLauncher.Routes.ROOT, route(""))
        assertEquals(PwaLauncher.Routes.ROOT, route("house"))
        assertEquals(PwaLauncher.Routes.ROOT, route("update"))
        assertEquals(PwaLauncher.Routes.ROOT, route("app_update"))
    }

    /**
     * The field is a discriminator, never a target. Even a notice that tries to
     * smuggle an address must not change where the tap lands.
     */
    @Test
    fun `a url in the payload is ignored`() {
        val o = JSONObject()
            .put("title", "Solaris")
            .put("body", "https://boese.example/apk")
            .put("kind", "app-update")
            .put("downloadUrl", "https://boese.example/apk")
            .put("url", "https://boese.example/apk")
        assertEquals(PwaLauncher.Routes.DOWNLOAD, NoticeNotifier.tapRoute(notice(o.toString())))
    }

    /** A notice without `kind` parses exactly as before — no field, no change. */
    @Test
    fun `kind is optional and defaults to empty`() {
        val ev = notice(JSONObject().put("title", "Tür offen").toString())
        assertEquals("", ev.kind)
    }
}
