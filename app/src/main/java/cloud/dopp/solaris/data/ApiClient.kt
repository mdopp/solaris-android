package cloud.dopp.solaris.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cloud.dopp.solaris.SolarisConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Solaris API client for the native surfaces, authenticated with the
 * `sol_device_…` bearer token from [TokenStore]. All calls are blocking and must
 * run off the main thread (widget provider goAsync thread / worker).
 */
class ApiClient(private val ctx: Context) {

    /** No server configured yet — the app must be connected first. */
    class NotConfiguredException : Exception("no server configured")

    /** No device token stored yet — the app must be paired first. */
    class NotPairedException : Exception("no device token")

    /** The server refused a sensitive action (garage/door open) without confirm. */
    class SensitiveException : Exception("sensitive action needs confirmation")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun base(): String =
        ServerStore.baseUrl(ctx)?.trimEnd('/') ?: throw NotConfiguredException()

    private fun token(): String = TokenStore.get(ctx) ?: throw NotPairedException()

    /** All native calls hit the proxy-bypassed, token-only `/napi/` prefix (#757). */
    private fun authed(path: String): Request.Builder =
        Request.Builder()
            .url(base() + SolarisConfig.NAPI + path)
            .header("Authorization", "Bearer ${token()}")

    /** One entity's live card via `/napi/portal/state?entity_id=…` → `{ok, card}`. */
    fun getCard(entityId: String): Card? {
        http.newCall(authed("/portal/state?entity_id=$entityId").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val card = JSONObject(body).optJSONObject("card") ?: return null
            return parseCard(card)
        }
    }

    /** Controllable actuators for the config picker via `/napi/portal/start/addable`. */
    fun listAddable(): List<Device> {
        http.newCall(authed("/portal/start/addable").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return parseAddable(JSONObject(body))
        }
    }

    /**
     * Run a scoped HA service on one entity. `service` is dotted
     * (`light.toggle`, `cover.open_cover`). Throws [SensitiveException] on the
     * server's 403 confirm gate; returns true on success.
     */
    fun call(
        entityId: String,
        service: String,
        confirmed: Boolean = false,
        data: JSONObject? = null,
    ): Boolean {
        val payload = JSONObject()
            .put("entity_id", entityId)
            .put("service", service)
        if (confirmed) payload.put("confirmed", true)
        if (data != null) payload.put("data", data)
        val reqBody = payload.toString().toRequestBody(JSON)
        http.newCall(authed("/ha/call").post(reqBody).build()).execute().use { resp ->
            if (resp.code == 403) throw SensitiveException()
            return resp.isSuccessful
        }
    }

    /** The house energy picture via `/napi/portal/energy` (the flow legs). */
    fun getEnergy(): Energy? {
        http.newCall(authed("/portal/energy").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val root = JSONObject(body)
            if (!root.optBoolean("ok", false)) return null
            val e = root.optJSONObject("energy") ?: return null
            val arr = e.optJSONArray("flow")
            val legs = mutableListOf<EnergyFlow>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    legs.add(
                        EnergyFlow(
                            label = o.optString("label"),
                            watts = o.optString("state").toDoubleOrNull(),
                            unit = o.optString("unit").ifBlank { "W" },
                            sense = o.optString("sense"),
                            entityId = o.optString("entity_id").ifBlank { null },
                        ),
                    )
                }
            }
            val totalsArr = e.optJSONArray("totals")
            val totals = mutableListOf<EnergyTotal>()
            if (totalsArr != null) {
                for (i in 0 until totalsArr.length()) {
                    val o = totalsArr.optJSONObject(i) ?: continue
                    totals.add(
                        EnergyTotal(
                            label = o.optString("label"),
                            value = o.optString("state").ifBlank { o.optString("value") }.toDoubleOrNull(),
                            unit = o.optString("unit").ifBlank { "kWh" },
                            // The server's totals carry no `key`/`sense` (#24) — keep
                            // reading them if present, else fall back to the entity_id
                            // so EnergyStyle can classify the slot from the id/label.
                            key = o.optString("key").ifBlank { o.optString("sense") }
                                .ifBlank { o.optString("entity_id") },
                            entityId = o.optString("entity_id").ifBlank { null },
                        ),
                    )
                }
            }
            return Energy(legs, totals)
        }
    }

    /**
     * The household's currently **active** devices (lights on, covers open,
     * switches on, …) for the overview widget (#28) — a **single** GET to the
     * server's collective endpoint `/napi/portal/active` (confirmed in
     * **solarisbay#773**), replacing the old per-entity N+1 fan-out. The endpoint
     * returns the already-active entities as a flat list, so no client-side
     * `isOn` filtering or roster join is needed. Bounded by [cap]. Ordered by
     * domain then name for a stable list. Empty/error → empty list (the widget
     * renders its "keine aktiven Geräte" state), so a hiccup never crashes it.
     */
    fun listActive(cap: Int = 40): List<Card> {
        try {
            http.newCall(authed("/portal/active").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                return parseActive(body).take(cap)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * Parse the `/napi/portal/active` payload into [Card]s. Accepts either a bare
     * JSON array or an object wrapping the list under `active`/`cards`/`devices`.
     * Each item is `{id|entity_id, name, room, domain, state}` — id may arrive as
     * either `id` or `entity_id`, so both are accepted. Room is carried on the
     * [Card.deviceClass] slot is *not* used; the active widget reads name/state.
     */
    internal fun parseActive(body: String): List<Card> {
        val root = JSONObject(body.trim().let { if (it.startsWith("[")) "{\"active\":$it}" else it })
        val arr = root.optJSONArray("active")
            ?: root.optJSONArray("cards")
            ?: root.optJSONArray("devices")
            ?: return emptyList()
        val out = ArrayList<Card>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val eid = o.optString("entity_id").ifBlank { o.optString("id") }
            if (eid.isBlank()) continue
            out.add(
                Card(
                    entityId = eid,
                    name = o.optString("name").ifBlank { eid },
                    domain = o.optString("domain").ifBlank { eid.substringBefore(".") },
                    deviceClass = o.optString("device_class").ifBlank { null },
                    state = o.optString("state").ifBlank { null },
                    unit = o.optString("unit").ifBlank { null },
                    brightness = o.optInt("brightness", -1).takeIf { it >= 0 },
                    position = o.optInt("current_position", -1).takeIf { it in 0..100 },
                    temperature = o.optDouble("current_temperature", Double.NaN).takeIf { !it.isNaN() },
                ),
            )
        }
        return out.sortedWith(compareBy({ it.domain }, { it.name }))
    }

    /**
     * Recent state history for one entity via `/napi/portal/entity-history`
     * (#755). Returns the numeric series (non-numeric states dropped) — used for
     * the line sparkline (dimmable brightness, temperature, power, …).
     */
    fun getEntityHistory(entityId: String, range: String = "48h"): List<Float> {
        val arr = fetchHistoryArray(entityId, range) ?: return emptyList()
        val out = ArrayList<Float>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            p.optString("state").toFloatOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * The **raw** state timeline for one entity (#29). Unlike [getEntityHistory]
     * this keeps every point's textual `state` (e.g. "on"/"off"/"open"/"closed"),
     * so a binary device (a plain light, a switch, a cover) can be shown as an
     * on/off timeline instead of an empty "kein Verlauf" block. Chronological.
     */
    fun getEntityStateHistory(entityId: String, range: String = "48h"): List<String> {
        val arr = fetchHistoryArray(entityId, range) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val s = p.optString("state", "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Fetch + parse the `history` array once; null on error/empty. */
    private fun fetchHistoryArray(entityId: String, range: String): org.json.JSONArray? {
        val path = "/portal/entity-history?entity_id=$entityId&range=$range"
        http.newCall(authed(path).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return JSONObject(body).optJSONArray("history")
        }
    }

    /**
     * A current snapshot frame for a camera entity via
     * `/napi/portal/camera/<entity_id>/snapshot` (solarisbay#770) — an image
     * (JPEG/PNG) authenticated with the device bearer. The bytes are decoded and
     * **downscaled** to a safe max edge ([CAMERA_MAX_EDGE_PX]) so the RemoteViews
     * bundle stays under the Binder transaction limit (oversized bitmaps →
     * "Widget kann nicht geladen werden"). Returns null on error / non-image.
     */
    fun getCameraSnapshot(entityId: String): Bitmap? {
        return try {
            val path = "/portal/camera/$entityId/snapshot"
            http.newCall(authed(path).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                decodeDownscaled(bytes, CAMERA_MAX_EDGE_PX)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCard(o: JSONObject): Card = parseCardJson(o)

    /** addable: `{ ok, rooms: [ {room, cards:[ {entity_id, name, domain, device_class} ]} ] }`. */
    private fun parseAddable(root: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val rooms = root.optJSONArray("rooms") ?: return out
        for (i in 0 until rooms.length()) {
            val roomObj = rooms.optJSONObject(i) ?: continue
            val room = roomObj.optString("room").ifBlank { null }
            val cards = roomObj.optJSONArray("cards") ?: continue
            for (j in 0 until cards.length()) {
                val c = cards.optJSONObject(j) ?: continue
                val eid = c.optString("entity_id")
                if (eid.isBlank()) continue
                out.add(
                    Device(
                        entityId = eid,
                        name = c.optString("name").ifBlank { eid },
                        domain = c.optString("domain").ifBlank { eid.substringBefore(".") },
                        deviceClass = c.optString("device_class").ifBlank { null },
                        room = room,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.room ?: "￿" }, { it.name }))
    }

    /**
     * Decode [bytes] into a bitmap whose longer edge is at most [maxEdge] px. Uses
     * a power-of-two `inSampleSize` (cheap, decoder-native) sized by [sampleSize],
     * so we never allocate the full-resolution bitmap. Returns null on non-image.
     */
    private fun decodeDownscaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Build a [Card] from the server's `card` JSON shape (same shape used by
         * `/napi/portal/state` and by the realtime `card_state` SSE frame, #48). Pure
         * (only `org.json`), so the realtime service and JVM tests can call it without
         * an [ApiClient] instance or the network.
         */
        fun parseCardJson(o: JSONObject): Card = Card(
            entityId = o.optString("entity_id"),
            name = o.optString("name").ifBlank { o.optString("entity_id") },
            domain = o.optString("domain").ifBlank { o.optString("entity_id").substringBefore(".") },
            deviceClass = o.optString("device_class").ifBlank { null },
            state = o.optString("state").ifBlank { null },
            unit = o.optString("unit").ifBlank { null },
            brightness = o.optInt("brightness", -1).takeIf { it >= 0 },
            position = o.optInt("current_position", -1).takeIf { it in 0..100 },
            temperature = o.optDouble("current_temperature", Double.NaN).takeIf { !it.isNaN() },
        )

        /** Safe max edge for a camera snapshot pushed into a RemoteViews bundle. */
        const val CAMERA_MAX_EDGE_PX = 640

        /**
         * Smallest power-of-two `inSampleSize` that brings the longer edge of a
         * [srcW]×[srcH] image at or below [maxEdge]. Pure (no Android types) so it
         * is JVM-unit-testable. Always ≥ 1.
         */
        fun sampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
            if (maxEdge <= 0) return 1
            val longer = maxOf(srcW, srcH)
            var sample = 1
            while (longer / (sample * 2) >= maxEdge) {
                sample *= 2
            }
            return sample
        }
    }
}
