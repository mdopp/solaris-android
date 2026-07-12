package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for one device widget. Icon + state + bar are
 * tinted by the device-type accent when active; the medium size adds real
 * controls — brightness ± for lights, up/stop/down for covers (widgets can't
 * host a drag slider, so stepped buttons are the interactive model).
 */
object WidgetRender {
    private const val OFF = 0xFF9E9E9E.toInt()

    fun build(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        domain: String,
        onToggle: PendingIntent,
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

        if (medium) {
            v.setOnClickPendingIntent(R.id.w_header, onToggle) // tap header = on/off
            wireControls(ctx, v, appWidgetId, dom)
        } else {
            v.setOnClickPendingIntent(R.id.w_root, onToggle)
        }
        return v
    }

    private fun wireControls(ctx: Context, v: RemoteViews, appWidgetId: Int, domain: String) {
        when (domain) {
            "light" -> {
                v.setViewVisibility(R.id.w_light_controls, View.VISIBLE)
                v.setViewVisibility(R.id.w_cover_controls, View.GONE)
                v.setOnClickPendingIntent(R.id.w_bright_down, op(ctx, appWidgetId, 2, WidgetActionReceiver.OP_BRIGHT_DOWN))
                v.setOnClickPendingIntent(R.id.w_bright_up, op(ctx, appWidgetId, 1, WidgetActionReceiver.OP_BRIGHT_UP))
            }
            "cover" -> {
                v.setViewVisibility(R.id.w_cover_controls, View.VISIBLE)
                v.setViewVisibility(R.id.w_light_controls, View.GONE)
                v.setOnClickPendingIntent(R.id.w_cover_up, op(ctx, appWidgetId, 3, WidgetActionReceiver.OP_COVER_OPEN))
                v.setOnClickPendingIntent(R.id.w_cover_stop, op(ctx, appWidgetId, 4, WidgetActionReceiver.OP_COVER_STOP))
                v.setOnClickPendingIntent(R.id.w_cover_down, op(ctx, appWidgetId, 5, WidgetActionReceiver.OP_COVER_CLOSE))
            }
            else -> {
                v.setViewVisibility(R.id.w_light_controls, View.GONE)
                v.setViewVisibility(R.id.w_cover_controls, View.GONE)
            }
        }
    }

    private fun op(ctx: Context, appWidgetId: Int, code: Int, op: String): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_TAP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetActionReceiver.EXTRA_OP, op)
        return PendingIntent.getBroadcast(
            ctx, appWidgetId * 10 + code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun iconFor(domain: String): Int = DeviceIcons.forDomain(domain)

    private fun accentFor(domain: String): Int = when (domain) {
        "light" -> 0xFFFFC107.toInt()
        "cover" -> 0xFF7E9CFF.toInt()
        "switch" -> 0xFF66BB6A.toInt()
        "climate" -> 0xFFFF8A65.toInt()
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
            "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °", it) } ?: (card.state ?: "?")
            else -> sensorLabel(card)
        }
    }

    private fun sensorLabel(card: Card): String {
        val s = card.state ?: return "—"
        return if (!card.unit.isNullOrBlank()) "$s ${card.unit}" else s
    }
}
