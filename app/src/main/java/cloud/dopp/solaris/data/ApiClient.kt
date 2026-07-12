package cloud.dopp.solaris.data

import android.content.Context
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

    /** One entity's live card via `/napi/concept/{id}` → `{ok, concept:{ha_card}}`. */
    fun getCard(entityId: String): Card? {
        http.newCall(authed("/concept/$entityId").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val concept = JSONObject(body).optJSONObject("concept") ?: return null
            val card = concept.optJSONObject("ha_card") ?: return null
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
            val arr = e.optJSONArray("flow") ?: return Energy(emptyList())
            val legs = mutableListOf<EnergyFlow>()
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
            return Energy(legs)
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
    }
}
