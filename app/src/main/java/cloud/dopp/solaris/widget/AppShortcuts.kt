package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlin.concurrent.thread

/**
 * Publishes the app-icon long-press menu (#97): one **dynamic** shortcut per
 * device the user has on the home screen.
 *
 * The list has to be dynamic — a static `res/xml/shortcuts.xml` is written at
 * build time and can never know the user's devices (the empty Bubblewrap stub
 * that used to sit there is gone with this change, like the notification icon in
 * #88). The device set comes from the live [DeviceWidgetProvider] instances plus
 * [WidgetStore], the same source [cloud.dopp.solaris.realtime.WatchSet] uses, so
 * the menu is exactly the devices the user already cared enough to place.
 *
 * Which entries exist, in which order, and how many — [DeviceShortcuts], pure
 * and JVM-tested. This object only does the parts that need a `Context`: ask the
 * launcher for the cap, read the store, build the icons and intents.
 *
 * Everything is best-effort: a launcher that rejects the publish (or one of the
 * OEM launchers that supports no shortcuts at all) must never take a widget or a
 * config screen down with it.
 */
object AppShortcuts {

    /**
     * Republish the menu. Call after every change to the widget set — bind,
     * delete, and once when the hub becomes visible so a restore/reinstall is
     * picked up too. Off the caller's thread: it touches SharedPreferences and
     * the launcher.
     */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        thread { runCatching { refresh(app) } }
    }

    /** Synchronous publish — [refreshAsync] is the normal entry point. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        // Ask, never assume: launchers commonly show four, but the allowed number
        // is the launcher's to state (0 below API 25 → nothing is published).
        val cap = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
        val entries = DeviceShortcuts.entries(boundDevices(app), cap)
        if (entries.isEmpty()) {
            runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(app) }
            return
        }
        val infos = entries.mapIndexed { i, e -> info(app, e, i) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, infos) }
    }

    /** Every configured device widget, newest instance last — order is [DeviceShortcuts]'. */
    fun boundDevices(ctx: Context): List<ShortcutDevice> {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, DeviceWidgetProvider::class.java))
        return ids.toList().mapNotNull { id ->
            val entityId = WidgetStore.entityId(app, id) ?: return@mapNotNull null
            if (entityId.isBlank()) return@mapNotNull null
            ShortcutDevice(
                appWidgetId = id,
                entityId = entityId,
                name = WidgetStore.name(app, id),
                domain = WidgetStore.domain(app, id),
                deviceClass = WidgetStore.deviceClass(app, id),
            )
        }
    }

    private fun info(ctx: Context, entry: ShortcutEntry, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, entry.id)
            .setShortLabel(entry.label)
            .setLongLabel(entry.label)
            // [ShortcutIcons], not [DeviceIcons] (#99) — the widget glyphs are white
            // on transparency and the launcher would show them as a white dot.
            .setIcon(IconCompat.createWithResource(ctx, ShortcutIcons.forDomain(entry.device.domain)))
            .setRank(rank)
            .setIntent(intentFor(ctx, entry))
            .build()

    /**
     * The intent behind one entry. **No shortcut carries a service** — a tap
     * either opens a screen, or asks [WidgetActionReceiver] for the widget's own
     * toggle, which is domain-checked there and gated by the server's 403. A
     * lock lands on the #92 chooser and nothing else, so the entry sits behind
     * the device lock and the latch stays unreachable from here.
     */
    fun intentFor(ctx: Context, entry: ShortcutEntry): Intent {
        val id = entry.device.appWidgetId
        return when (entry.action) {
            ShortcutAction.CHOOSE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entry.device.entityId)
                .putExtra(WidgetActionActivity.EXTRA_LOCK_CHOOSE, true)

            ShortcutAction.TOGGLE -> Intent(ctx, WidgetActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)

            ShortcutAction.OPEN -> Intent(ctx, PwaTrampolineActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(
                    PwaTrampolineActivity.EXTRA_PATH,
                    PwaLauncher.Routes.device(entry.device.entityId),
                )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
