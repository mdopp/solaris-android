package cloud.dopp.solaris.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Eine neue Version liegt bereit" (#141, contract solarisbay#1326).
 *
 * The server publishes `GET <base>/download/version` — **public, no device token**,
 * deliberately outside the `/napi/` prefix, which is token-only:
 *
 * ```json
 * {"versionName": "2.38.1", "publishedAt": "…", "downloadUrl": "…", "sizeBytes": 123}
 * ```
 *
 * Two decisions worth stating, because both are easy to get wrong later:
 *
 * 1. **No household domain in the app.** The contract names `www.dopp.cloud`
 *    absolutely, but this app pairs with *a* Solaris server, not with one house.
 *    So the check follows [ServerStore.baseUrl] — whatever the user paired with.
 * 2. **Silence beats a broken promise.** Anything unexpected — no server, no
 *    network, a 404 because the endpoint isn't deployed yet, unparseable JSON, a
 *    version that isn't actually newer — returns `null`, and the hint stays
 *    hidden. A line offering an update that leads nowhere is worse than no line
 *    (the whole reason #141 waited for solarisbay#1326: `/download` used to 302
 *    into a private GitHub release and answered 404).
 */
object UpdateCheck {

    /** A newer build the server is offering. Never constructed for same-or-older. */
    data class Available(val versionName: String, val downloadUrl: String, val sizeBytes: Long)

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Ask the paired server what it publishes. Blocking — call off the main thread.
     * Returns null unless there is genuinely something newer than [installed].
     */
    fun fetch(ctx: Context, installed: String): Available? = runCatching {
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return null
        val req = Request.Builder().url("$base/download/version").get().build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string() ?: return null
        }
        val json = JSONObject(body)
        val version = json.optString("versionName").trim()
        if (version.isEmpty() || !isNewer(version, installed)) return null
        Available(
            versionName = version,
            downloadUrl = downloadUrl(json.optString("downloadUrl"), base),
            sizeBytes = json.optLong("sizeBytes", 0L),
        )
    }.getOrNull()

    /**
     * Where the tap goes. The server may point somewhere other than its own host
     * (the file and the API need not share a vhost), so its answer is honoured —
     * but only over **https**, and otherwise the paired server's own `/download`.
     * The app must not follow an arbitrary scheme just because a response said so.
     */
    internal fun downloadUrl(fromServer: String?, base: String): String {
        val u = fromServer?.trim().orEmpty()
        return if (u.startsWith("https://", ignoreCase = true)) u else "$base/download"
    }

    /**
     * Is [server] a later version than [installed]? Compared **numerically per
     * segment**, never as strings: "2.4.0" sorts after "2.38.1" alphabetically and
     * would offer a downgrade as an update.
     *
     * A trailing suffix on a segment is ignored ("1-rc2" counts as 1) and a missing
     * segment counts as 0, so "2.39" is newer than "2.38.1". Anything that does not
     * parse yields false — silence, not a guess.
     *
     * Pure → JVM-testable.
     */
    fun isNewer(server: String, installed: String): Boolean {
        val a = segments(server)
        val b = segments(installed)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(v: String): List<Int> {
        val parts = v.trim().removePrefix("v").split('.')
        val out = ArrayList<Int>(parts.size)
        for (p in parts) {
            val n = p.takeWhile { it.isDigit() }.toIntOrNull() ?: return emptyList()
            out.add(n)
        }
        return out
    }
}
