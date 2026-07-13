package cloud.dopp.solaris.realtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the realtime SSE service after a reboot (#48) — but only if the user
 * opted in and the app is paired. [RealtimeService.ensure] enforces both, so a
 * disabled/unpaired install boots without any service or notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { RealtimeService.ensure(context) }
        }
    }
}
