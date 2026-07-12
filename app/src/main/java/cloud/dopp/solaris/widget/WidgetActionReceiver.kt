package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import kotlin.concurrent.thread

/**
 * Headless tap handler for the device widget — runs the toggle/open in the
 * background with **no visible UI** (so a tap doesn't flash a screen). Only a
 * garage/door confirm needs UI, which it defers to [WidgetActionActivity].
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val entityId = WidgetStore.entityId(context, id) ?: return
        val domain = WidgetStore.domain(context, id)
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(app)
                when (domain) {
                    "light", "switch" -> api.call(entityId, "$domain.toggle")
                    "cover" -> {
                        val card = api.getCard(entityId)
                        val service = if (card?.isOn == true) "cover.close_cover" else "cover.open_cover"
                        try {
                            api.call(entityId, service)
                        } catch (e: ApiClient.SensitiveException) {
                            app.startActivity(
                                Intent(app, WidgetActionActivity::class.java)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                    .putExtra(EXTRA_ENTITY, entityId)
                                    .putExtra(EXTRA_SERVICE, service)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            return@thread // the confirm dialog refreshes after
                        }
                    }
                    else -> { /* climate & others are read-only for now */ }
                }
                DeviceWidgetProvider.requestRefresh(app, id)
            } catch (e: ApiClient.NotConfiguredException) {
                openHome(app)
            } catch (e: ApiClient.NotPairedException) {
                openHome(app)
            } catch (e: Exception) {
                // network/other — keep the last rendered state
            } finally {
                pending.finish()
            }
        }
    }

    private fun openHome(app: Context) {
        app.startActivity(
            Intent(app, OnboardingHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_TAP = "cloud.dopp.solaris.widget.TAP"
        const val EXTRA_ENTITY = "entity_id"
        const val EXTRA_SERVICE = "service"
    }
}
