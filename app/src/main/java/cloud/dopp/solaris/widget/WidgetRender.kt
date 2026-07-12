package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card
import cloud.dopp.solaris.data.isSensitiveDevice
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for one device widget. Icon + state + bar are
 * tinted by the device-type accent when active; the medium size adds real
 * controls — brightness ± for lights, up/stop/down for covers (widgets can't
 * host a drag slider, so stepped buttons are the interactive model).
 */
object WidgetRender {
    private const val OFF = 0xFF9E9E9E.toInt()

    /** The widget size tiers. Selected by [sizeTier] from the host's min size. */
    enum class Tier { TINY, SMALL, WIDE, MEDIUM, LARGE }

    /**
     * Pick the layout tier from the host-reported min size (dp). A big box gets
     * the [LARGE] layout (medium controls PLUS a 48h-history chart, #29); a
     * tall-enough box gets the stacked [MEDIUM] controls; an otherwise
     * wide-but-flat box (e.g. 4×1) still gets an inline control row via [WIDE];
     * a mid box falls back to [SMALL] (name + state only); the smallest single
     * cell (1×1) gets [TINY] — name + one toggle + a docked state bar (#31).
     * Pure so it can be JVM-tested.
     */
    fun sizeTier(minW: Int, minH: Int): Tier = when {
        minW >= 180 && minH >= 220 -> Tier.LARGE
        minW >= 180 && minH >= 110 -> Tier.MEDIUM
        minW >= 250 -> Tier.WIDE
        minW < 110 && minH < 110 -> Tier.TINY
        else -> Tier.SMALL
    }

    /**
     * True when the tier hosts the 48h-history chart (#29). Now **MEDIUM and
     * LARGE** — a medium-height card shows the history too (the chart bitmap is
     * scaled down for the smaller area). Small/wide/tiny stay chart-free.
     */
    fun showsChart(tier: Tier): Boolean = tier == Tier.MEDIUM || tier == Tier.LARGE

    /**
     * Does a **body/central-area tap** toggle the device on/off for this domain
     * (#33)? Lights and switches are simple on↔off actuators, so the big obvious
     * area should switch them directly — the PWA moves to the name/header tap.
     * Covers (no simple toggle) and sensors keep the body tap = open-PWA. Pure so
     * it's JVM-testable.
     */
    fun togglesOnBodyTap(domain: String): Boolean = domain == "light" || domain == "switch"

    /**
     * @param onBodyTap fired by a tap on the widget body/header — opens the PWA
     *   (#27), NOT a control action. The control buttons keep their own actions.
     *   When the widget is unconfigured this is instead the picker intent.
     */
    fun build(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        domain: String,
        onBodyTap: PendingIntent,
        history: List<Float>? = null,
        stateHistory: List<String>? = null,
    ): RemoteViews {
        val tier = tierFor(ctx, appWidgetId)
        val dom = card?.domain?.ifBlank { null } ?: domain
        val on = card?.isOn == true

        // TINY (1×1, #31) has its own shape: name instead of icon, one primary
        // toggle, and the state docked as a bottom bar. Built separately.
        val sensitive = isSensitive(ctx, appWidgetId, card, dom)

        if (tier == Tier.TINY) return buildTiny(ctx, appWidgetId, card, fallbackName, dom, on, onBodyTap, sensitive)

        val layout = when (tier) {
            Tier.LARGE -> R.layout.widget_device_large
            Tier.MEDIUM -> R.layout.widget_device_medium
            Tier.WIDE -> R.layout.widget_device_wide
            else -> R.layout.widget_device_small
        }
        val v = RemoteViews(ctx.packageName, layout)

        val accent = if (on) accentFor(dom) else OFF

        v.setImageViewResource(R.id.w_icon, iconFor(dom))
        v.setInt(R.id.w_icon, "setColorFilter", accent)
        v.setTextViewText(R.id.w_name, (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } })
        v.setTextViewText(R.id.w_state, stateLabel(card))
        v.setTextColor(R.id.w_state, accent)

        // Lock badge (#38): sensitive devices (garage/door/gate) need a confirm.
        v.setViewVisibility(R.id.w_lock, if (sensitive) View.VISIBLE else View.GONE)

        val level = card?.level
        if (level != null) {
            v.setViewVisibility(R.id.w_bar, View.VISIBLE)
            v.setProgressBar(R.id.w_bar, 100, level, false)
        } else {
            v.setViewVisibility(R.id.w_bar, View.GONE)
        }

        // Refresh icon (#26): re-fetch this widget's state headlessly. Present on
        // all three tiers; the click is a discreet, no-flash broadcast.
        v.setOnClickPendingIntent(
            R.id.w_refresh, op(ctx, appWidgetId, 7, WidgetActionReceiver.OP_REFRESH),
        )

        // Tap model per domain (#33): for lights/switches the big central area
        // toggles on/off and the *name* opens the PWA; for covers/sensors the body
        // opens the PWA (#27) and the per-domain control buttons do the rest.
        val toggles = togglesOnBodyTap(dom)
        val bodyToggle = op(ctx, appWidgetId, 9, WidgetActionReceiver.OP_TOGGLE)
        if (tier == Tier.MEDIUM || tier == Tier.WIDE || tier == Tier.LARGE) {
            if (toggles) {
                // Central icon+state area toggles; name opens the PWA.
                v.setOnClickPendingIntent(R.id.w_icon, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_state, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_name, onBodyTap)
            } else {
                v.setOnClickPendingIntent(R.id.w_header, onBodyTap)
            }
            wireControls(ctx, v, appWidgetId, dom)
        } else {
            if (toggles) {
                // Small tier: whole card toggles, name opens the PWA.
                v.setOnClickPendingIntent(R.id.w_root, bodyToggle)
                v.setOnClickPendingIntent(R.id.w_name, onBodyTap)
            } else {
                v.setOnClickPendingIntent(R.id.w_root, onBodyTap)
            }
        }

        // 48h history — MEDIUM + LARGE (#29). Numeric entities get the line
        // sparkline; binary devices (light/switch/cover) get an on/off timeline;
        // when there's genuinely no history the chart area is collapsed so the
        // card isn't a big empty block. LARGE has a taller area than MEDIUM.
        if (tier == Tier.LARGE || tier == Tier.MEDIUM) {
            renderChart(ctx, v, dom, on, history, stateHistory, large = tier == Tier.LARGE)
        }
        return v
    }

    /**
     * The 1×1 (single-cell) device widget (#31): the **name** in place of the
     * icon (2 lines), a **single toggle** button showing ▲/▼ (cover) or on/off
     * glyph, and the **state as a full-width bottom bar** (position / brightness).
     * The toggle runs the domain's primary action; body tap still opens the PWA.
     */
    private fun buildTiny(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        dom: String,
        on: Boolean,
        onBodyTap: PendingIntent,
        sensitive: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_device_tiny)
        val accent = if (on) accentFor(dom) else OFF

        v.setTextViewText(R.id.w_name, (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } })
        // Lock badge (#38): sensitive devices (garage/door/gate) need a confirm.
        v.setViewVisibility(R.id.w_lock, if (sensitive) View.VISIBLE else View.GONE)

        // Single toggle glyph + the primary action for the domain.
        val (glyph, op) = tinyToggle(dom, card)
        v.setTextViewText(R.id.w_tiny_toggle, glyph)
        v.setTextColor(R.id.w_tiny_toggle, accent)
        v.setOnClickPendingIntent(R.id.w_tiny_toggle, op(ctx, appWidgetId, 8, op))

        // Body (name) tap → PWA (#27).
        v.setOnClickPendingIntent(R.id.w_name, onBodyTap)

        // State docked as a bottom bar (position / brightness).
        val level = card?.level
        if (level != null) {
            v.setViewVisibility(R.id.w_bar, View.VISIBLE)
            v.setProgressBar(R.id.w_bar, 100, level, false)
        } else {
            v.setViewVisibility(R.id.w_bar, View.GONE)
        }
        return v
    }

    /**
     * The tiny-tier single toggle: a glyph + the op that performs the domain's
     * primary action. Cover toggles open/close by current state (▲ when closed,
     * ▼ when open); light/switch toggle on↔off.
     */
    private fun tinyToggle(domain: String, card: Card?): Pair<String, String> = when (domain) {
        "cover" -> {
            val open = card?.isOn == true
            if (open) "▼" to WidgetActionReceiver.OP_COVER_CLOSE
            else "▲" to WidgetActionReceiver.OP_COVER_OPEN
        }
        "light", "switch" -> (if (card?.isOn == true) "◍" else "○") to WidgetActionReceiver.OP_TOGGLE
        else -> "⟳" to WidgetActionReceiver.OP_REFRESH
    }

    /**
     * Draw the 48h history on the MEDIUM/LARGE layout (#29). Numeric entities
     * (dimmable brightness, temperature, power) → the line sparkline; binary
     * devices (a plain light, a switch, a cover) → an on/off timeline derived
     * from the raw state history; genuinely no history → **collapse** the area
     * (hide chart AND hint) so the card falls back to the compact layout rather
     * than showing a big empty block. The bitmap is smaller for the MEDIUM area.
     */
    private fun renderChart(
        ctx: Context,
        v: RemoteViews,
        domain: String,
        on: Boolean,
        history: List<Float>?,
        stateHistory: List<String>?,
        large: Boolean,
    ) {
        // Bitmap size per tier — kept well under ChartRenderer.MAX_EDGE (#32).
        val w = if (large) 560 else 480
        val h = if (large) 200 else 120

        if (history != null && history.size >= 2) {
            val line = if (on) accentFor(domain) else OFF
            val fill = (line and 0x00FFFFFF) or 0x33000000
            v.setImageViewBitmap(R.id.w_chart, ChartRenderer.sparkline(w, h, history, line, fill))
            showChart(v)
            return
        }
        val timeline = stateHistory?.map { ChartRenderer.stateIsOn(it) }
        if (timeline != null && timeline.size >= 2) {
            val onColor = accentFor(domain)
            val offColor = 0x33FFFFFF
            v.setImageViewBitmap(R.id.w_chart, ChartRenderer.onOffTimeline(w, h, timeline, onColor, offColor))
            showChart(v)
            return
        }
        // Genuinely no history → collapse (neither chart nor "kein Verlauf" block).
        collapseChart(v)
    }

    private fun showChart(v: RemoteViews) {
        v.setViewVisibility(R.id.w_chart, View.VISIBLE)
        v.setViewVisibility(R.id.w_chart_hint, View.GONE)
    }

    private fun collapseChart(v: RemoteViews) {
        v.setViewVisibility(R.id.w_chart, View.GONE)
        v.setViewVisibility(R.id.w_chart_hint, View.GONE)
    }

    /**
     * Does this widget's device need a confirm (#38)? Prefer the live [card]'s
     * class; when there's no card yet (cache/loading render) fall back to the
     * bound widget metadata (domain + deviceClass) persisted at config time.
     */
    private fun isSensitive(ctx: Context, appWidgetId: Int, card: Card?, dom: String): Boolean {
        if (card != null) return card.isSensitiveCover
        return isSensitiveDevice(dom, WidgetStore.deviceClass(ctx, appWidgetId))
    }

    /** Resolve the layout tier for [appWidgetId] from the host's reported min size. */
    fun tierFor(ctx: Context, appWidgetId: Int): Tier {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return sizeTier(minW, minH)
    }

    /**
     * Show exactly one control row for the domain (and hide the others) so the
     * card's bottom band is filled without leaving an empty control hole for
     * non-controllable domains. The switch row (medium/wide only) is optional —
     * older/small layouts omit it, so we guard with [hasSwitchRow].
     */
    private fun wireControls(ctx: Context, v: RemoteViews, appWidgetId: Int, domain: String) {
        // Default: everything hidden; enable just the row we need below.
        v.setViewVisibility(R.id.w_light_controls, View.GONE)
        v.setViewVisibility(R.id.w_cover_controls, View.GONE)
        setSwitchRowVisibility(v, View.GONE)
        when (domain) {
            "light" -> {
                v.setViewVisibility(R.id.w_light_controls, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_bright_down, op(ctx, appWidgetId, 2, WidgetActionReceiver.OP_BRIGHT_DOWN))
                v.setOnClickPendingIntent(R.id.w_bright_up, op(ctx, appWidgetId, 1, WidgetActionReceiver.OP_BRIGHT_UP))
            }
            "cover" -> {
                v.setViewVisibility(R.id.w_cover_controls, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_cover_up, op(ctx, appWidgetId, 3, WidgetActionReceiver.OP_COVER_OPEN))
                v.setOnClickPendingIntent(R.id.w_cover_stop, op(ctx, appWidgetId, 4, WidgetActionReceiver.OP_COVER_STOP))
                v.setOnClickPendingIntent(R.id.w_cover_down, op(ctx, appWidgetId, 5, WidgetActionReceiver.OP_COVER_CLOSE))
            }
            "switch" -> {
                setSwitchRowVisibility(v, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_switch_toggle, op(ctx, appWidgetId, 6, WidgetActionReceiver.OP_TOGGLE))
            }
            // sensors / other domains: no control row — the state + bar fill the card.
        }
    }

    /** Toggle the switch row where the layout has one (medium/wide); no-op otherwise. */
    private fun setSwitchRowVisibility(v: RemoteViews, vis: Int) {
        v.setViewVisibility(R.id.w_switch_controls, vis)
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
