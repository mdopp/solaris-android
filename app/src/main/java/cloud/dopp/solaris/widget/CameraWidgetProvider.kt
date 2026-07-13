package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.solaris.R

/**
 * The camera **trigger** widget (#36): a small tile (camera icon + "Kamera") that
 * opens the PWA cameras view on tap — no per-camera configuration and no snapshot
 * loading, mirroring the voice widget. This avoids the empty "keine Kamera
 * gefunden" picker while `/napi` can't list cameras (server gap solarisbay#779);
 * a per-camera variant can follow once cameras are listable.
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

    private fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        // Tap → the PWA cameras view (#36); no data to fetch, so no "lädt …".
        val tap = PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.CAMERA_ROOT)
        val v = RemoteViews(context.packageName, R.layout.widget_camera)
        v.setTextViewText(R.id.cam_name, context.getString(R.string.camera_widget_label))
        v.setOnClickPendingIntent(R.id.cam_root, tap)
        try {
            mgr.updateAppWidget(appWidgetId, v)
        } catch (t: Throwable) {
            // Never leave the host on "Widget kann nicht geladen werden" (#32).
            WidgetFallback.show(context, appWidgetId, tap)
        }
    }
}
