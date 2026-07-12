package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for one device widget. The icon + state text are
 * tinted by the device-type accent when active; a level bar shows brightness
 * (lights) or position (covers). Small = icon+name+state+bar; medium = larger.
 */
object WidgetRender {
    private const val OFF = 0xFF9E9E9E.toInt() // grey = inactive

    fun build(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        domain: String,
        onTap: PendingIntent,
    ): RemoteViews {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val medium = minW >= 180 && minH >= 110
        val v = RemoteViews(ctx.packageName, if (medium) R.layout.widget_device_medium else R.layout.widget_device_small)

        val dom = card?.domain?.ifBlank { null } ?: domain
        val on = card?.isOn == true
        val accent = if (on) accentFor(dom) else OFF

        v.setImageViewResource(R.id.w_icon, iconFor(dom))
        v.setInt(R.id.w_icon, "setColorFilter", accent)

        v.setTextViewText(R.id.w_name, (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } })
        v.setTextViewText(R.id.w_state, stateLabel(card))
        v.setTextColor(R.id.w_state, accent)

        val level = card?.level
        if (level != null) {
            v.setViewVisibility(R.id.w_bar, View.VISIBLE)
            v.setProgressBar(R.id.w_bar, 100, level, false)
        } else {
            v.setViewVisibility(R.id.w_bar, View.GONE)
        }

        v.setOnClickPendingIntent(R.id.w_root, onTap)
        return v
    }

    private fun iconFor(domain: String): Int = when (domain) {
        "light" -> R.drawable.ic_light
        "switch" -> R.drawable.ic_switch
        "cover" -> R.drawable.ic_cover
        "climate" -> R.drawable.ic_climate
        else -> R.drawable.ic_device
    }

    private fun accentFor(domain: String): Int = when (domain) {
        "light" -> 0xFFFFC107.toInt()   // amber
        "cover" -> 0xFF7E9CFF.toInt()   // blue
        "switch" -> 0xFF66BB6A.toInt()  // green
        "climate" -> 0xFFFF8A65.toInt() // orange
        else -> 0xFFFFC107.toInt()
    }

    private fun stateLabel(card: Card?): String {
        if (card == null) return "…"
        return when (card.domain) {
            "light" -> if (card.isOn) card.brightnessPct?.let { "an · $it %" } ?: "an" else "aus"
            "switch" -> if (card.isOn) "an" else "aus"
            "cover" -> card.position?.let { p ->
                when {
                    p >= 99 -> "offen"
                    p <= 1 -> "zu"
                    else -> "$p % offen"
                }
            } ?: if (card.isOn) "offen" else "zu"
            "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °", it) }
                ?: (card.state ?: "?")
            else -> sensorLabel(card)
        }
    }

    /** Sensor-style value; never renders a trailing "null" unit. */
    private fun sensorLabel(card: Card): String {
        val s = card.state ?: return "—"
        return if (!card.unit.isNullOrBlank()) "$s ${card.unit}" else s
    }
}
