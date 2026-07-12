package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.solaris.R

/**
 * The camera **trigger** widget (#36). Each instance is bound (via
 * [CameraWidgetConfigActivity]) to one HA `camera.*` entity. It is a pure tile —
 * a camera icon + the bound camera name + a subtle "Kamera öffnen" hint — and a
 * tap opens that camera's live view in the PWA (`/#/p/camera/<entity_id>`,
 * solarisbay#782). It no longer fetches or renders a snapshot, so there's no
 * "lädt …" hang; the heavy live stream lives in the PWA. Mirrors the voice
 * widget's trigger-tile model.
 *
 * `ApiClient.getCameraSnapshot` is kept for a possible future optional preview,
 * but it no longer drives this widget's state.
 */
class CameraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) = render(context, mgr, appWidgetId)

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbind(context, it) }
    }

    private fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        // Any bind/render failure must yield a clean fallback, never an uncaught
        // throw that leaves the host showing "Widget kann nicht geladen werden" (#32).
        val entityId = try {
            WidgetStore.entityId(context, appWidgetId)
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, configPending(context, appWidgetId))
            return
        }

        val v = RemoteViews(context.packageName, R.layout.widget_camera)
        if (entityId == null) {
            // Not bound yet — show the generic label; tap opens the picker.
            v.setTextViewText(R.id.cam_name, context.getString(R.string.camera_widget_label))
            v.setOnClickPendingIntent(R.id.cam_root, configPending(context, appWidgetId))
        } else {
            val name = WidgetStore.name(context, appWidgetId)
                .ifBlank { context.getString(R.string.camera_widget_label) }
            v.setTextViewText(R.id.cam_name, name)
            // Tap → the PWA camera live view for the bound entity (#36).
            val tap = PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.camera(entityId))
            v.setOnClickPendingIntent(R.id.cam_root, tap)
        }
        try {
            mgr.updateAppWidget(appWidgetId, v)
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, configPending(context, appWidgetId))
        }
    }

    private fun configPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, CameraWidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 410_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
