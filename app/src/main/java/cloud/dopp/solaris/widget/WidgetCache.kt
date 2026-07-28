package cloud.dopp.solaris.widget

import android.content.Context
import cloud.dopp.solaris.data.Card
import cloud.dopp.solaris.data.SbHome
import org.json.JSONObject

/**
 * Persistent **last-good render cache** for widgets (#46).
 *
 * The bug: on a transient token/network failure a provider re-rendered its
 * *empty* state — a device widget flipped to "↻ tippen", a ServiceBay widget to
 * "—" — so the homescreen looked wiped "after a while / on certain events".
 * [StateEpochGuard]'s in-memory memory doesn't survive process death either, so a
 * reboot then a slow first fetch blanked everything.
 *
 * The fix is the same **stale-then-fresh** idea the active-devices collection
 * already uses ([WidgetStore.activeCacheJson]): whenever a fetch succeeds we
 * remember the rendered payload here; when the next fetch fails the provider
 * re-renders from this cache instead of an empty placeholder. Only a widget that
 * has *never* loaded shows the placeholder.
 *
 * Storage is a **plain** `MODE_PRIVATE` prefs file — deliberately NOT encrypted,
 * so it is not coupled to the AndroidKeyStore wipe that used to take the token
 * (and thus the widgets) down with it. Values are non-secret UI state.
 */
object WidgetCache {
    private const val PREFS = "solaris_widget_cache"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Device card (DeviceWidgetProvider) ---------------------------------

    fun putCard(ctx: Context, id: Int, card: Card) {
        val o = JSONObject()
            .put("entityId", card.entityId)
            .put("name", card.name)
            .put("domain", card.domain)
            .putOpt("deviceClass", card.deviceClass)
            .putOpt("state", card.state)
            .putOpt("unit", card.unit)
        card.brightness?.let { o.put("brightness", it) }
        card.position?.let { o.put("position", it) }
        card.temperature?.let { o.put("temperature", it) }
        card.updatedAtMs?.let { o.put("updatedAtMs", it) }
        write(ctx, "card_$id", o)
    }

    fun getCard(ctx: Context, id: Int): Card? {
        val o = read(ctx, "card_$id") ?: return null
        return try {
            Card(
                entityId = o.getString("entityId"),
                name = o.optString("name"),
                domain = o.optString("domain"),
                deviceClass = o.optStringOrNull("deviceClass"),
                state = o.optStringOrNull("state"),
                unit = o.optStringOrNull("unit"),
                brightness = if (o.has("brightness")) o.optInt("brightness") else null,
                position = if (o.has("position")) o.optInt("position") else null,
                temperature = if (o.has("temperature")) o.optDouble("temperature") else null,
                updatedAtMs = if (o.has("updatedAtMs")) o.optLong("updatedAtMs") else null,
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- ServiceBay overview (SbOverviewWidgetProvider) ----------------------

    fun putHome(ctx: Context, id: Int, home: SbHome) {
        val o = JSONObject()
            .put("servicesUp", home.servicesUp)
            .put("servicesFailed", home.servicesFailed)
            .put("servicesDown", home.servicesDown)
            .put("pendingApprovals", home.pendingApprovals)
            .put("pendingUpdates", home.pendingUpdates)
        write(ctx, "sbhome_$id", o)
    }

    fun getHome(ctx: Context, id: Int): SbHome? {
        val o = read(ctx, "sbhome_$id") ?: return null
        return try {
            SbHome(
                servicesUp = o.optInt("servicesUp"),
                servicesFailed = o.optInt("servicesFailed"),
                servicesDown = o.optInt("servicesDown"),
                pendingApprovals = o.optInt("pendingApprovals"),
                pendingUpdates = o.optInt("pendingUpdates"),
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- ServiceBay service counts (SbServicesWidgetProvider) ----------------

    fun putServiceCounts(ctx: Context, id: Int, counts: Triple<Int, Int, Int>) {
        val o = JSONObject()
            .put("ok", counts.first)
            .put("warn", counts.second)
            .put("fail", counts.third)
        write(ctx, "sbsvc_$id", o)
    }

    fun getServiceCounts(ctx: Context, id: Int): Triple<Int, Int, Int>? {
        val o = read(ctx, "sbsvc_$id") ?: return null
        return try {
            Triple(o.optInt("ok"), o.optInt("warn"), o.optInt("fail"))
        } catch (e: Exception) {
            null
        }
    }

    // --- lifecycle -----------------------------------------------------------

    /** Drop every cached payload for [id] (widget removed / re-bound). */
    fun clear(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("card_$id")
            .remove("sbhome_$id")
            .remove("sbsvc_$id")
            .apply()
    }

    // --- helpers -------------------------------------------------------------

    private fun write(ctx: Context, key: String, o: JSONObject) {
        runCatching { p(ctx).edit().putString(key, o.toString()).apply() }
    }

    private fun read(ctx: Context, key: String): JSONObject? {
        val s = runCatching { p(ctx).getString(key, null) }.getOrNull() ?: return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
}
