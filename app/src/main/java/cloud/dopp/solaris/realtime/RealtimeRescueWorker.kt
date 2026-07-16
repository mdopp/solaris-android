package cloud.dopp.solaris.realtime

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Periodic backstop for the realtime SSE service (#48). Aggressive OEM battery
 * managers can kill the foreground service; this worker (scheduled while
 * Live-Updates is on, ~every 15 min, surviving reboots via WorkManager) simply
 * re-runs [RealtimeService.ensure] — which restarts the service if the user is
 * still opted-in + paired, or stays quiet otherwise. Best-effort: a blocked
 * foreground-service start (Android 12+ without a battery exemption) is swallowed.
 */
class RealtimeRescueWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        runCatching { RealtimeService.ensure(applicationContext) }
        return Result.success()
    }
}
