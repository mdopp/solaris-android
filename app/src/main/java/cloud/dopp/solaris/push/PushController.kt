package cloud.dopp.solaris.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import kotlin.concurrent.thread

/**
 * Drives UnifiedPush registration for true background push (#push). Enabling picks
 * a distributor (first installed — e.g. self-hosted ntfy), ensures the local Web
 * Push keys, and registers; the [SolarisMessagingReceiver] then gets an endpoint
 * which we register with the server (`/napi/push/subscribe`). Disabling unregisters
 * and tells the server to drop the subscription.
 */
object PushController {

    /** Is a UnifiedPush distributor installed on the device? */
    fun available(ctx: Context): Boolean =
        UnifiedPush.getDistributors(ctx.applicationContext).isNotEmpty()

    /** Start registration. Returns false (no-op) if no distributor is installed. */
    fun enable(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val distributors = UnifiedPush.getDistributors(app)
        if (distributors.isEmpty()) return false
        UnifiedPush.saveDistributor(app, distributors.first())
        PushKeyStore.ensureKeys(app)
        UnifiedPush.registerApp(app)
        return true
    }

    fun disable(ctx: Context) {
        val app = ctx.applicationContext
        val endpoint = PushKeyStore.endpoint(app)
        runCatching { UnifiedPush.unregisterApp(app) }
        thread {
            if (endpoint != null) runCatching { PushApi(app).unsubscribe(endpoint) }
            PushKeyStore.clear(app)
        }
    }

    /** A distributor handed us an endpoint → register it with the server. */
    fun onEndpoint(ctx: Context, endpoint: String) {
        val app = ctx.applicationContext
        PushKeyStore.ensureKeys(app)
        PushKeyStore.setEndpoint(app, endpoint)
        thread { PushApi(app).subscribe(endpoint, PushKeyStore.p256dh(app), PushKeyStore.auth(app)) }
    }

    fun onUnregistered(ctx: Context) {
        val app = ctx.applicationContext
        val endpoint = PushKeyStore.endpoint(app)
        thread { if (endpoint != null) runCatching { PushApi(app).unsubscribe(endpoint) } }
    }
}
