package cloud.dopp.solaris.push

import android.content.Context
import org.json.JSONObject
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * UnifiedPush callback (#push). The distributor delivers the endpoint URL and the
 * raw (RFC 8291-encrypted) Web Push bytes here; we subscribe on a new endpoint and
 * decrypt + dispatch each message. A bad frame is swallowed — never crash.
 */
class SolarisMessagingReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        PushController.onEndpoint(context, endpoint)
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // Distributor refused (e.g. not reachable) — nothing to do; the SSE path
        // still covers foreground updates.
    }

    override fun onUnregistered(context: Context, instance: String) {
        PushController.onUnregistered(context)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        runCatching {
            if (!PushKeyStore.hasKeys(context)) return
            val plain = WebPushCrypto.decrypt(
                message,
                PushKeyStore.privateKey(context),
                PushKeyStore.uaRaw(context),
                PushKeyStore.authSecret(context),
            )
            Notifier.handlePush(context, JSONObject(String(plain, Charsets.UTF_8)))
        }
    }
}
