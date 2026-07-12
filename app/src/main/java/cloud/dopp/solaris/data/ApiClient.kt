package cloud.dopp.solaris.data

import android.content.Context
import cloud.dopp.solaris.SolarisConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
     * switches on, …) for the overview widget (#28). There is no single "list
     * active" endpoint client-side, so this reuses [listAddable] for the roster
     * and fetches each entity's live [Card] via [getCard], keeping only the
     * active ones. Bounded by [cap] to keep the widget refresh cheap; the
     * fetched cards are ordered by domain then name. If a dedicated
     * `/napi/portal/active` (or a batch states call) lands server-side this
     * should switch to it — tracked in **solarisbay#773** (collective states /
     * active endpoint). Until then the per-entity [getCard] reads are **fired
     * concurrently** on a small bounded pool (was sequential N+1 → ~10s) so the
     * widget refresh stays fast; each read is timeout- and exception-guarded so a
     * single slow/broken entity can't stall or fail the whole list.
     */
    fun listActive(cap: Int = 40): List<Card> {
        val roster = listAddable().take(cap)
        if (roster.isEmpty()) return emptyList()

        // Bounded fan-out: OkHttp reads are blocking, so a handful of workers turns
        // the sequential N reads into ~N/POOL waves. Kept small to be gentle on the
        // server (solarisbay#773 will collapse this to one call).
        val pool = Executors.newFixedThreadPool(minOf(ACTIVE_POOL, roster.size))
        try {
            val tasks = roster.map { d ->
                Callable { try { getCard(d.entityId) } catch (e: Exception) { null } }
            }
            val out = mutableListOf<Card>()
            for (f in pool.invokeAll(tasks, ACTIVE_FANOUT_TIMEOUT_S, TimeUnit.SECONDS)) {
                val card = try { f.get() } catch (e: Exception) { null } ?: continue
                if (card.isOn) out.add(card)
            }
            return out.sortedWith(compareBy({ it.domain }, { it.name }))
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Recent state history for one entity via `/napi/portal/entity-history`
     * (#755). Returns the numeric series (non-numeric states dropped).
     */
    fun getEntityHistory(entityId: String, range: String = "48h"): List<Float> {
        val path = "/portal/entity-history?entity_id=$entityId&range=$range"
        http.newCall(authed(path).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("history") ?: return emptyList()
            val out = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                p.optString("state").toFloatOrNull()?.let { out.add(it) }
            }
            return out
        }
    }

    private fun parseCard(o: JSONObject): Card = Card(
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

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Worker count for the [listActive] per-entity state fan-out. */
        private const val ACTIVE_POOL = 8

        /** Hard ceiling for the whole [listActive] fan-out wave (seconds). */
        private const val ACTIVE_FANOUT_TIMEOUT_S = 12L
    }
}
