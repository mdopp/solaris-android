package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import kotlin.concurrent.thread

/**
 * House energy widget (#8). Shows the live grid net (small) or the full
 * PV/Haus/Netz/Akku flow (large). No per-instance config — one house picture.
 * A tap opens the Solaris app (or pairing if not yet paired).
 */
class EnergyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetId)
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
        val paired = TokenStore.isPaired(context)
        val tap = tapPending(context, appWidgetId, paired)
        // Immediate render (loading / pair hint), then async fetch.
        mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, null, paired, tap))
        if (!paired) return
        val pending = goAsync()
        thread {
            try {
                val energy = try {
                    ApiClient(context.applicationContext).getEnergy()
                } catch (e: Exception) {
                    null
                }
                mgr.updateAppWidget(appWidgetId, EnergyRender.build(context, appWidgetId, energy, true, tap))
            } finally {
                pending.finish()
            }
        }
    }

    private fun tapPending(context: Context, appWidgetId: Int, paired: Boolean): PendingIntent {
        val intent = if (paired) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, OnboardingHomeActivity::class.java)
        } else {
            Intent(context, OnboardingHomeActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.ENERGY_REFRESH"
    }
}
