package cloud.dopp.solaris.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.ui.ApprovalsActivity
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider
import cloud.dopp.solaris.widget.DeviceWidgetProvider
import cloud.dopp.solaris.widget.PwaLauncher
import org.json.JSONObject

/**
 * Turns a decrypted Web Push payload (#push) into the right side effect:
 * `servicebay` → an approval notification (tap → in-app list), `card_state` →
 * refresh the device widgets (the push carries no card), anything else (chat /
 * reminder) → a generic notification that opens the app / its `data.url`.
 *
 * Payload shape (companion-api.md §4): `{title, body, data:{kind, id?, url?, …}}`.
 */
object Notifier {
    private const val CHANNEL = "solaris_push"
    private const val APPROVAL_BASE = 5100
    private const val GENERIC_BASE = 5200

    fun handlePush(ctx: Context, payload: JSONObject) {
        val data = payload.optJSONObject("data")
        val kind = data?.optString("kind") ?: ""
        val title = payload.optString("title").ifBlank { ctx.getString(R.string.appName) }
        val body = payload.optString("body")
        when (kind) {
            "card_state" -> {
                DeviceWidgetProvider.refreshAll(ctx)
                ActiveDevicesWidgetProvider.refreshAll(ctx)
            }
            "servicebay" -> {
                val id = data?.optString("id") ?: ""
                approvalNotif(ctx, id, title, body.ifBlank { ctx.getString(R.string.approval_notif_generic) })
            }
            else -> genericNotif(ctx, title, body, data?.optString("url"))
        }
    }

    private fun approvalNotif(ctx: Context, id: String, title: String, body: String) {
        val tap = PendingIntent.getActivity(
            ctx, APPROVAL_BASE + (id.hashCode() and 0xffff),
            Intent(ctx, ApprovalsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(ctx, APPROVAL_BASE + (id.hashCode() and 0xffff), title, body, tap)
    }

    private fun genericNotif(ctx: Context, title: String, body: String, url: String?) {
        val code = GENERIC_BASE + (title.hashCode() and 0xffff)
        val tap = if (!url.isNullOrBlank()) {
            PwaLauncher.tapPending(ctx, code, url)
        } else {
            PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, OnboardingHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        notify(ctx, code, title, body, tap)
    }

    private fun notify(ctx: Context, id: Int, title: String, body: String, tap: PendingIntent) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.push_channel_desc) },
        )
    }
}
