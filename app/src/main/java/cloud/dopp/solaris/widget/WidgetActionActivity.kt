package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * Confirm dialog for a sensitive action (garage/door/gate, lock, alarm panel).
 * Launched by the headless [WidgetActionReceiver] only when the server returns
 * the 403 confirm gate — so ordinary toggles never spawn a visible screen.
 * Whether a confirm is needed stays server-authoritative; only the wording is
 * decided here, via [ConfirmVerb].
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
        // Wording follows the service's own direction (#85) — a substring test on
        // "close" mislabels lock.lock and alarm_arm_*, so ConfirmVerb maps the
        // dotted domain.action instead and the button follows the same verb.
        val action = ConfirmVerb.of(service)
        val verb = getString(ConfirmWording.verbRes(action))
        val positive = ConfirmWording.positiveRes(action)
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
