package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * The camera snapshot widget (#30). Each instance is bound (via
 * [CameraWidgetConfigActivity]) to one HA `camera.*` entity; it renders the
 * current frame from `/napi/portal/camera/<entity_id>/snapshot`
 * (solarisbay#770). A discreet refresh icon pulls a fresh frame; a body/image
 * tap opens the entity's live view in the PWA (device route, #769 best-effort).
 * Snapshots are heavy, so the auto-refresh period is moderate (see the
 * widget-info) and the user drives refreshes manually.
 */
class CameraWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbind(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        // Any data/render failure must yield a clean fallback, never an uncaught
        // throw that leaves the host showing "Widget kann nicht geladen werden" (#32).
        val entityId = try {
            WidgetStore.entityId(context, appWidgetId)
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            return
        }

        if (entityId == null) {
            // Not configured (or orphaned) — placeholder; tap opens the picker.
            try {
                mgr.updateAppWidget(
                    appWidgetId,
                    scaffold(context, appWidgetId, null, "—", false, configPending(context, appWidgetId)),
                )
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            }
            return
        }

        val name = WidgetStore.name(context, appWidgetId).ifBlank { context.getString(R.string.camera_widget_label) }
        val tap = PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.device(entityId))

        // Immediate "lädt …" scaffold (no frame yet), then async snapshot fetch.
        try {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, null, name, false, tap))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
        }

        val pending = goAsync()
        thread {
            try {
                val bmp = try {
                    ApiClient(context.applicationContext).getCameraSnapshot(entityId)
                } catch (e: Exception) {
                    null
                }
                // fetched=true → a null frame now means "kein Bild", not "lädt …".
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, bmp, name, true, tap))
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Build the camera RemoteViews: the frame (or the placeholder when [frame] is
     * null), the header name, the refresh + body taps. When there's a bitmap the
     * placeholder is hidden; otherwise it shows "lädt …/kein Bild".
     */
    private fun scaffold(
        context: Context,
        appWidgetId: Int,
        frame: Bitmap?,
        name: String,
        fetched: Boolean,
        bodyTap: PendingIntent,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_camera)
        v.setTextViewText(R.id.cam_name, name)
        if (frame != null) {
            v.setImageViewBitmap(R.id.cam_image, frame)
            v.setViewVisibility(R.id.cam_image, View.VISIBLE)
            v.setViewVisibility(R.id.cam_placeholder, View.GONE)
        } else {
            v.setViewVisibility(R.id.cam_image, View.GONE)
            v.setViewVisibility(R.id.cam_placeholder, View.VISIBLE)
            // Distinguish "still loading" from "fetched but no frame" (kein Bild).
            v.setTextViewText(
                R.id.cam_placeholder,
                context.getString(if (fetched) R.string.camera_no_image else R.string.camera_loading),
            )
        }
        v.setOnClickPendingIntent(R.id.cam_root, bodyTap)
        v.setOnClickPendingIntent(R.id.cam_image, bodyTap)
        v.setOnClickPendingIntent(R.id.cam_refresh, refreshPending(context, appWidgetId))
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, CameraWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 400_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.CAMERA_REFRESH"

        /** Ask a bound instance to re-fetch its snapshot (explicit self-broadcast). */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            val i = Intent(context, CameraWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            context.sendBroadcast(i)
        }
    }
}
