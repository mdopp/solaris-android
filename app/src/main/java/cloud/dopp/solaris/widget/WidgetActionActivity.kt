package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.pair.PairingActivity
import kotlin.concurrent.thread

/**
 * Invisible tap handler for a device widget. Decides the service from the live
 * state (light/switch → toggle, cover → open/close) and runs it. The garage/door
 * confirm is **server-authoritative**: a sensitive open returns 403, and we then
 * show a confirm dialog and re-send with `confirmed=true`.
 */
class WidgetActionActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val entityId = WidgetStore.entityId(this, appWidgetId)
        if (entityId == null) {
            finish()
            return
        }
        val domain = WidgetStore.domain(this, appWidgetId)
        thread { handle(entityId, domain) }
    }

    private fun handle(entityId: String, domain: String) {
        val api = ApiClient(applicationContext)
        try {
            when (domain) {
                "light", "switch" -> api.call(entityId, "$domain.toggle")
                "cover" -> {
                    val card = api.getCard(entityId)
                    val service = if (card?.isOn == true) "cover.close_cover" else "cover.open_cover"
                    try {
                        api.call(entityId, service)
                    } catch (e: ApiClient.SensitiveException) {
                        runOnUiThread { confirm(entityId, service) }
                        return
                    }
                }
                else -> { /* climate & others are read-only in C1 */ }
            }
        } catch (e: ApiClient.NotPairedException) {
            runOnUiThread {
                startActivity(
                    Intent(this, PairingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (e: Exception) {
            // network/other — keep the last rendered state
        }
        done()
    }

    private fun confirm(entityId: String, service: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_confirm_title)
            .setMessage(getString(R.string.widget_confirm_msg, WidgetStore.name(this, appWidgetId)))
            .setPositiveButton(R.string.widget_confirm_yes) { _, _ ->
                thread {
                    try {
                        ApiClient(applicationContext).call(entityId, service, confirmed = true)
                    } catch (e: Exception) {
                        // ignore
                    }
                    done()
                }
            }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }

    private fun done() {
        DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
        runOnUiThread { finish() }
    }
}
