package cloud.dopp.solaris.widget

import android.content.Context

/**
 * Per-instance binding for the device widget: maps an `appWidgetId` to the
 * chosen entity plus cached meta (name/domain/device_class) so the provider can
 * render and route taps without a network round-trip for the identity.
 */
object WidgetStore {
    private const val PREFS = "solaris_widgets"

    private fun p(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bind(ctx: Context, id: Int, entityId: String, name: String, domain: String, deviceClass: String?) {
        p(ctx).edit()
            .putString("e_$id", entityId)
            .putString("n_$id", name)
            .putString("d_$id", domain)
            .putString("c_$id", deviceClass ?: "")
            .apply()
    }

    fun entityId(ctx: Context, id: Int): String? = p(ctx).getString("e_$id", null)
    fun name(ctx: Context, id: Int): String = p(ctx).getString("n_$id", "") ?: ""
    fun domain(ctx: Context, id: Int): String = p(ctx).getString("d_$id", "") ?: ""
    fun deviceClass(ctx: Context, id: Int): String? =
        p(ctx).getString("c_$id", "")?.ifBlank { null }

    fun unbind(ctx: Context, id: Int) {
        p(ctx).edit()
            .remove("e_$id").remove("n_$id").remove("d_$id").remove("c_$id")
            .apply()
    }
}
