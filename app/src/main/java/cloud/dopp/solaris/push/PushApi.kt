package cloud.dopp.solaris.push

import android.content.Context
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Device-token client for the native Web Push subscription endpoints (#push,
 * contract companion-api.md §4): `POST /napi/push/{subscribe,unsubscribe}`. The
 * `endpoint` is the UnifiedPush distributor URL; `keys` are this install's
 * [PushKeyStore] public + auth. Blocking — call off the main thread.
 */
class PushApi(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun authed(path: String): Request.Builder? {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val token = TokenStore.get(ctx) ?: return null
        return Request.Builder()
            .url(base + SolarisConfig.NAPI + path)
            .header("Authorization", "Bearer $token")
    }

    fun subscribe(endpoint: String, p256dh: String, auth: String): Boolean {
        val builder = authed("/push/subscribe") ?: return false
        val body = JSONObject()
            .put("endpoint", endpoint)
            .put("keys", JSONObject().put("p256dh", p256dh).put("auth", auth))
            .toString().toRequestBody(json)
        return try {
            http.newCall(builder.post(body).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun unsubscribe(endpoint: String): Boolean {
        val builder = authed("/push/unsubscribe") ?: return false
        val body = JSONObject().put("endpoint", endpoint).toString().toRequestBody(json)
        return try {
            http.newCall(builder.post(body).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
