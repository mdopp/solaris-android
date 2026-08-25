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
 * Headless handler for every device-widget control — toggle, brightness ±,
 * cover up/stop/down, and the lock's bolt (#84). Runs in the background with
 * **no visible UI** (so taps don't flash a screen). Only the server's 403
 * confirm gate — a garage/door open, any `lock.*` — defers to
 * [WidgetActionActivity].
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val op = intent.getStringExtra(EXTRA_OP) ?: OP_TOGGLE
        // Refresh is a pure re-render (#26) — no entity call, just re-fetch state.
        if (op == OP_REFRESH) {
            DeviceWidgetProvider.requestRefresh(context.applicationContext, id)
            return
        }
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
                    OP_LOCK -> callOrConfirm(api, app, id, entityId, "lock.lock")
                    OP_UNLOCK -> callOrConfirm(api, app, id, entityId, "lock.unlock")
                    else -> when (domain) { // OP_TOGGLE
                        "light", "switch" -> { api.call(entityId, "$domain.toggle"); true }
                        "cover" -> {
                            val card = api.getCard(entityId)
                            val service = if (card?.isOn == true) "cover.close_cover" else "cover.open_cover"
                            callOrConfirm(api, app, id, entityId, service)
                        }
                        "lock" -> callOrConfirm(
                            api, app, id, entityId, lockToggleService(api.getCard(entityId)?.state),
                        )
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
                pending?.finish()
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
        const val OP_REFRESH = "refresh"
        const val OP_BRIGHT_UP = "bright_up"
        const val OP_BRIGHT_DOWN = "bright_down"
        const val OP_COVER_OPEN = "cover_open"
        const val OP_COVER_STOP = "cover_stop"
        const val OP_COVER_CLOSE = "cover_close"
        const val OP_LOCK = "lock_lock"
        const val OP_UNLOCK = "lock_unlock"

        private const val STEP = 20 // brightness step, %

        /**
         * Which bolt service a lock's toggle runs (#84). Only a confirmed `locked`
         * unlocks; every other reading — `unlocked`, `jammed`, `unknown`, a missing
         * state — falls to `lock.lock`, the securing direction. It never returns
         * `lock.open`: that pulls the latch and opens the door, which no widget tap
         * may reach. Pure → JVM-testable.
         */
        fun lockToggleService(state: String?): String =
            if (state?.trim()?.lowercase() == "locked") "lock.unlock" else "lock.lock"
    }
}
