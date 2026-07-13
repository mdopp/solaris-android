package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * Confirm dialog for a sensitive cover open (garage/door/gate). Launched by the
 * headless [WidgetActionReceiver] only when the server returns the 403 confirm
 * gate — so ordinary toggles never spawn a visible screen.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val entityId = intent.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY)
        val service = intent.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE)
        if (entityId == null || service == null) {
            finish()
            return
        }
        // Confirm wording follows the action (open vs close), derived from the service.
        val closing = service.contains("close")
        val verb = getString(if (closing) R.string.widget_verb_close else R.string.widget_verb_open)
        val positive = if (closing) R.string.widget_confirm_close else R.string.widget_confirm_yes
        AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(R.string.widget_confirm_title)
            .setMessage(getString(R.string.widget_confirm_msg, WidgetStore.name(this, appWidgetId), verb))
            .setPositiveButton(positive) { _, _ ->
                thread {
                    try {
                        ApiClient(applicationContext).call(entityId, service, confirmed = true)
                    } catch (e: Exception) {
                        // ignore
                    }
                    DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
