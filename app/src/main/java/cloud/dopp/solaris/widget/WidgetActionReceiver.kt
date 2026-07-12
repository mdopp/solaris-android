package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Headless handler for every device-widget control — toggle, brightness ±, and
 * cover up/stop/down. Runs in the background with **no visible UI** (so taps
 * don't flash a screen). Only a garage/door open confirm defers to
 * [WidgetActionActivity].
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val op = intent.getStringExtra(EXTRA_OP) ?: OP_TOGGLE
        val entityId = WidgetStore.entityId(context, id) ?: return
        val domain = WidgetStore.domain(context, id)
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(app)
                val done = when (op) {
                    OP_BRIGHT_UP -> { api.call(entityId, "light.turn_on", data = step(+STEP)); true }
                    OP_BRIGHT_DOWN -> { api.call(entityId, "light.turn_on", data = step(-STEP)); true }
                    OP_COVER_STOP -> { api.call(entityId, "cover.stop_cover"); true }
                    OP_COVER_CLOSE -> { api.call(entityId, "cover.close_cover"); true }
                    OP_COVER_OPEN -> callOrConfirm(api, app, id, entityId, "cover.open_cover")
                    else -> when (domain) { // OP_TOGGLE
                        "light", "switch" -> { api.call(entityId, "$domain.toggle"); true }
                        "cover" -> {
                            val card = api.getCard(entityId)
                            val service = if (card?.isOn == true) "cover.close_cover" else "cover.open_cover"
                            callOrConfirm(api, app, id, entityId, service)
                        }
                        else -> true
                    }
                }
                if (done) DeviceWidgetProvider.requestRefresh(app, id)
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

    /** Run the service; on the 403 sensitive gate, launch the confirm dialog. */
    private fun callOrConfirm(api: ApiClient, app: Context, id: Int, entityId: String, service: String): Boolean {
        return try {
            api.call(entityId, service)
            true
        } catch (e: ApiClient.SensitiveException) {
            app.startActivity(
                Intent(app, WidgetActionActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(EXTRA_ENTITY, entityId)
                    .putExtra(EXTRA_SERVICE, service)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            false
        }
    }

    private fun step(pct: Int) = JSONObject().put("brightness_step_pct", pct)

    private fun openHome(app: Context) {
        app.startActivity(
            Intent(app, OnboardingHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val ACTION_TAP = "cloud.dopp.solaris.widget.TAP"
        const val EXTRA_OP = "op"
        const val EXTRA_ENTITY = "entity_id"
        const val EXTRA_SERVICE = "service"

        const val OP_TOGGLE = "toggle"
        const val OP_BRIGHT_UP = "bright_up"
        const val OP_BRIGHT_DOWN = "bright_down"
        const val OP_COVER_OPEN = "cover_open"
        const val OP_COVER_STOP = "cover_stop"
        const val OP_COVER_CLOSE = "cover_close"

        private const val STEP = 20 // brightness step, %
    }
}
