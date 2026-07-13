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

    /** A parsed `card_state` frame: which entity, and its fresh card. */
    data class CardStateEvent(val entityId: String, val card: Card)

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
}
