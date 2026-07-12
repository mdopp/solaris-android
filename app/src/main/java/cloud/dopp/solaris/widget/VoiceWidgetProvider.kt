package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cloud.dopp.solaris.R

/**
 * The voice/mic widget (#17). A tiny homescreen button: tapping it captures speech
 * (device STT) and opens a NEW household chat in the PWA with the recognized text
 * auto-sent (server `?ask=` contract, solarisbay#766). No data to poll — the widget
 * is static, so [onUpdate] just (re)binds the tap PendingIntent.
 *
 * The tap targets the invisible [VoiceTrampolineActivity] (mirrors
 * [PwaTrampolineActivity]) because both the RECORD_AUDIO prompt and the speech
 * RecognizerIntent must run from an Activity context, not the widget process.
 */
class VoiceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val v = RemoteViews(context.packageName, R.layout.widget_voice)
            val tap = tapPending(context, id)
            v.setOnClickPendingIntent(R.id.voice_root, tap)
            v.setOnClickPendingIntent(R.id.voice_mic, tap)
            try {
                mgr.updateAppWidget(id, v)
            } catch (t: Throwable) {
                WidgetFallback.show(context, id, tap)
            }
        }
    }

    /** A body-tap [PendingIntent] into the speech trampoline; unique per widget id. */
    private fun tapPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, VoiceTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 500_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
