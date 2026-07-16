package cloud.dopp.solaris.realtime

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.ApprovalsActivity

/**
 * Screen-off backstop for the realtime service (#48): while the screen is off the
 * live SSE is dropped (battery/Doze), and this runs a light ~4-minute poll via a
 * Doze-tolerant alarm — checks `/napi/servicebay/home` for a rise in pending
 * approvals and alerts, then re-arms. On screen-on the service reconnects the SSE
 * and cancels this. Best-effort: deep Doze may stretch the interval beyond 4 min
 * (Android throttles allow-while-idle alarms).
 */
object RealtimePoll {
    private const val PREFS = "realtime_poll"
    private const val K_LAST_APPROVALS = "last_approvals"
    private const val REQ = 48_010
    private const val APPROVAL_CHANNEL = "solaris_approvals"
    private const val NOTIF_ID = 4910

    private fun eligible(ctx: Context): Boolean =
        ServerStore.realtimeEnabled(ctx) && ServerStore.isConfigured(ctx) && TokenStore.isPaired(ctx)

    private fun alarmPi(ctx: Context): PendingIntent {
        val i = Intent(ctx, RealtimeIdleReceiver::class.java).setAction(ACTION_POLL)
        return PendingIntent.getBroadcast(
            ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Arm the next screen-off poll at the user-configured interval (Doze-tolerant,
     * inexact — no exact-alarm permission). A configured interval of 0 disables the
     * poll entirely (pure screen-on live).
     */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val seconds = ServerStore.pollSeconds(ctx)
        if (seconds <= 0) { cancel(ctx); return }
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + seconds * 1000L
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(ctx)) }
    }

    fun cancel(context: Context) {
        val ctx = context.applicationContext
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(ctx)) }
    }

    /**
     * One poll pass (called from [RealtimeIdleReceiver] on a worker thread). If the
     * screen came back on, hand back to the live service; otherwise check for new
     * approvals, alert, and re-arm.
     */
    fun run(context: Context) {
        val ctx = context.applicationContext
        if (!eligible(ctx)) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && pm.isInteractive) {
            // Screen is on again → resume the live SSE, stop polling.
            RealtimeService.ensure(ctx)
            return
        }
        val home = try { SbApiClient(ctx).getHome() } catch (e: Exception) { null }
        if (home != null) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getInt(K_LAST_APPROVALS, 0)
            if (home.pendingApprovals > last && home.pendingApprovals > 0) {
                notifyApprovals(ctx, home.pendingApprovals)
            }
            prefs.edit().putInt(K_LAST_APPROVALS, home.pendingApprovals).apply()
        }
        schedule(ctx) // re-arm the next pass
    }

    private fun notifyApprovals(ctx: Context, count: Int) {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, NOTIF_ID,
            Intent(ctx, ApprovalsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = ctx.resources.getQuantityString(R.plurals.approvals_pending, count, count)
        val n = NotificationCompat.Builder(ctx, APPROVAL_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.approval_notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(APPROVAL_CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(APPROVAL_CHANNEL, ctx.getString(R.string.approval_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.approval_channel_desc) },
        )
    }

    const val ACTION_POLL = "cloud.dopp.solaris.realtime.IDLE_POLL"
}
