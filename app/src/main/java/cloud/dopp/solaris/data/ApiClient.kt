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

    /** No device token stored yet — the app must be paired first. */
    class NotPairedException : Exception("no device token")

    /** The server refused a sensitive action (garage/door open) without confirm. */
    class SensitiveException : Exception("sensitive action needs confirmation")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun token(): String = TokenStore.get(ctx) ?: throw NotPairedException()

    private fun authed(path: String): Request.Builder =
        Request.Builder()
            .url(SolarisConfig.BASE_URL + path)
            .header("Authorization", "Bearer ${token()}")

    /** One entity's live card via `/api/concept/{id}` (works for any entity). */
    fun getCard(entityId: String): Card? {
        http.newCall(authed("/api/concept/$entityId").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val card = JSONObject(body).optJSONObject("ha_card") ?: return null
            return parseCard(card)
        }
    }

    /** Controllable actuators for the config picker via `/api/portal/start/addable`. */
    fun listAddable(): List<Device> {
        http.newCall(authed("/api/portal/start/addable").get().build()).execute().use { resp ->
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
        http.newCall(authed("/api/ha/call").post(reqBody).build()).execute().use { resp ->
            if (resp.code == 403) throw SensitiveException()
            return resp.isSuccessful
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

    /** addable groups actuators by room: `{ rooms: { <room>: [ {entity_id, name, …} ] } }`. */
    private fun parseAddable(root: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val rooms = root.optJSONObject("rooms") ?: return out
        for (room in rooms.keys()) {
            val arr = rooms.optJSONArray(room) ?: continue
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val eid = d.optString("entity_id")
                if (eid.isBlank()) continue
                out.add(
                    Device(
                        entityId = eid,
                        name = d.optString("name").ifBlank { d.optString("label").ifBlank { eid } },
                        domain = eid.substringBefore("."),
                        deviceClass = d.optString("device_class").ifBlank { null },
                        room = room,
                    ),
                )
            }
        }
        return out.sortedWith(compareBy({ it.room ?: "" }, { it.name }))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
