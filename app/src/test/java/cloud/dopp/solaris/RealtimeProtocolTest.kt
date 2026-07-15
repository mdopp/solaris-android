package cloud.dopp.solaris

import cloud.dopp.solaris.realtime.RealtimeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for the realtime SSE protocol helpers (#48): the `card_state` frame
 * parse and the exponential reconnect backoff. Robolectric only for `org.json`
 * (no Android components touched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeProtocolTest {

    @Test
    fun parsesCardStateFrame() {
        val data = """
            {"entity_id": "light.kitchen",
             "card": {"entity_id": "light.kitchen", "name": "Küche",
                      "domain": "light", "state": "on", "brightness": 128}}
        """.trimIndent()
        val ev = RealtimeProtocol.parseCardState(data)!!
        assertEquals("light.kitchen", ev.entityId)
        assertEquals("Küche", ev.card.name)
        assertEquals("light", ev.card.domain)
        assertTrue(ev.card.isOn)
        assertEquals(50, ev.card.brightnessPct) // 128/255 ≈ 50%
    }

    @Test
    fun fallsBackToCardEntityIdWhenTopLevelMissing() {
        val data = """{"card": {"entity_id": "cover.garage", "domain": "cover", "state": "open"}}"""
        val ev = RealtimeProtocol.parseCardState(data)!!
        assertEquals("cover.garage", ev.entityId)
        assertTrue(ev.card.isOn)
    }

    @Test
    fun returnsNullForMalformedOrEmpty() {
        assertNull(RealtimeProtocol.parseCardState(null))
        assertNull(RealtimeProtocol.parseCardState(""))
        assertNull(RealtimeProtocol.parseCardState("not json"))
        // No card object → nothing to render.
        assertNull(RealtimeProtocol.parseCardState("""{"entity_id": "light.x"}"""))
        // No entity_id anywhere → can't route it.
        assertNull(RealtimeProtocol.parseCardState("""{"card": {"domain": "light"}}"""))
    }

    @Test
    fun parsesServicebayApprovalFrame() {
        val ev = RealtimeProtocol.parseServicebay(
            """{"id":"a13b382c","kind":"container_update","summary":"Update nginx?"}""",
        )!!
        assertEquals("a13b382c", ev.id)
        assertEquals("container_update", ev.kind)
        assertEquals("Update nginx?", ev.summary)
    }

    @Test
    fun servicebayNullWhenNoId() {
        assertNull(RealtimeProtocol.parseServicebay(null))
        assertNull(RealtimeProtocol.parseServicebay(""))
        assertNull(RealtimeProtocol.parseServicebay("not json"))
        assertNull(RealtimeProtocol.parseServicebay("""{"summary":"no id"}"""))
    }

    @Test
    fun backoffGrowsExponentiallyThenCaps() {
        assertEquals(2_000L, RealtimeProtocol.backoffMillis(0))
        assertEquals(4_000L, RealtimeProtocol.backoffMillis(1))
        assertEquals(8_000L, RealtimeProtocol.backoffMillis(2))
        assertEquals(16_000L, RealtimeProtocol.backoffMillis(3))
        assertEquals(32_000L, RealtimeProtocol.backoffMillis(4))
        // Caps at 60s and never overflows for large/absurd attempt counts.
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(5))
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(20))
        assertEquals(60_000L, RealtimeProtocol.backoffMillis(999))
    }
}
