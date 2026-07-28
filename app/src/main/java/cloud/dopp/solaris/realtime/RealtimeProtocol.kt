package cloud.dopp.solaris.realtime

import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Card
import org.json.JSONObject

/**
 * Pure (Android-free) helpers for the realtime SSE client (#48): the reconnect
 * backoff schedule and the `card_state` frame parse. Kept off any Android type so
 * they're covered by plain JVM unit tests.
 */
object RealtimeProtocol {

    /** The SSE event kind we act on; every other kind (chat/done/…) is ignored. */
    const val EVENT_CARD_STATE = "card_state"

    /** ServiceBay approval event (companion-api.md): `data:{id,kind,summary}`. */
    const val EVENT_SERVICEBAY = "servicebay"

    /** A parsed `card_state` frame: which entity, and its fresh card. */
    data class CardStateEvent(val entityId: String, val card: Card)

    /** A parsed `servicebay` approval frame. */
    data class ApprovalEvent(val id: String, val kind: String, val summary: String)

    /**
     * Parse a `servicebay` frame's `data` payload (`{"id","kind","summary"}`) into
     * an [ApprovalEvent]. Returns null on malformed JSON or a missing `id` — the
     * caller treats null as "nothing to notify", never a crash.
     */
    fun parseServicebay(data: String?): ApprovalEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val o = JSONObject(data)
            val id = o.optString("id")
            if (id.isBlank()) return null
            ApprovalEvent(id, o.optString("kind"), o.optString("summary"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a `card_state` frame's `data` payload
     * (`{"entity_id": "...", "card": { …card shape… }}`) into a [CardStateEvent].
     * Returns null on malformed JSON, a missing/blank `entity_id`, or a missing
     * `card` object — the caller treats null as "nothing to do", never a crash.
     */
    fun parseCardState(data: String?): CardStateEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val root = JSONObject(data)
            val entityId = root.optString("entity_id")
                .ifBlank { root.optJSONObject("card")?.optString("entity_id") ?: "" }
            if (entityId.isBlank()) return null
            val cardObj = root.optJSONObject("card") ?: return null
            CardStateEvent(entityId, ApiClient.parseCardJson(cardObj))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Exponential reconnect backoff (#48): `base * 2^attempt`, capped at [cap].
     * `attempt` is 0-based (the first retry after a drop). E.g. 2s→4s→8s→…→cap.
     * A [special404] backoff (default 5 min) is returned for the "endpoint not
     * deployed yet" case (solarisbay#806 pending) so we don't spam a 404 loop.
     */
    fun backoffMillis(
        attempt: Int,
        baseMs: Long = 2_000L,
        capMs: Long = 60_000L,
    ): Long {
        if (attempt < 0) return baseMs
        // Guard the shift against overflow for large attempt counts.
        if (attempt >= 32) return capMs
        val grown = baseMs shl attempt
        return if (grown <= 0L || grown > capMs) capMs else grown
    }

    /** Long backoff for HTTP 404 (endpoint not live yet) — avoid spamming logs. */
    const val NOT_DEPLOYED_BACKOFF_MS = 5 * 60 * 1000L

    /**
     * A connection must stay open at least this long to count as **healthy** — only
     * then do we reset the reconnect backoff (#79). Resetting on every `onOpen`
     * let a server that accepts-then-immediately-closes the stream (`getrennt (200)`)
     * drive a ~2s reconnect storm that never backed off; requiring a stable session
     * makes a flapping stream escalate 2s→4s→…→cap instead of hammering the battery.
     */
    const val STABLE_SESSION_MS = 20_000L

    /**
     * True when a session that opened at [connectedAtMs] (0 = never opened) has been
     * up long enough at [nowMs] to be considered healthy — i.e. its drop should
     * reset the backoff. Pure so the churn guard is unit-testable.
     */
    fun healthySession(connectedAtMs: Long, nowMs: Long): Boolean =
        connectedAtMs != 0L && nowMs - connectedAtMs >= STABLE_SESSION_MS
}
