package cloud.dopp.solaris.push

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Persists this install's Web Push subscription material (#push): the P-256
 * keypair + 16-byte auth secret used to decrypt pushes, plus the current
 * UnifiedPush endpoint URL (needed to unsubscribe). Plain prefs — the private key
 * only decrypts inbound notifications; the sensitive `sol_device_` token stays in
 * [cloud.dopp.solaris.data.TokenStore].
 */
object PushKeyStore {
    private const val PREFS = "push_keys"
    private const val K_PRIV = "priv_pkcs8"
    private const val K_UA = "ua_raw"
    private const val K_AUTH = "auth"
    private const val K_ENDPOINT = "endpoint"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Create the keypair + auth secret once; idempotent. */
    fun ensureKeys(ctx: Context) {
        val prefs = p(ctx)
        if (prefs.contains(K_PRIV)) return
        val kp = WebPushCrypto.generateKeyPair()
        val uaRaw = WebPushCrypto.uncompressed(kp.public as java.security.interfaces.ECPublicKey)
        val auth = WebPushCrypto.generateAuthSecret()
        prefs.edit()
            .putString(K_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(K_UA, WebPushCrypto.b64url(uaRaw))
            .putString(K_AUTH, WebPushCrypto.b64url(auth))
            .apply()
    }

    fun hasKeys(ctx: Context): Boolean = p(ctx).contains(K_PRIV)

    fun privateKey(ctx: Context): ECPrivateKey {
        val pkcs8 = Base64.decode(p(ctx).getString(K_PRIV, "") ?: "", Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey
    }

    fun uaRaw(ctx: Context): ByteArray = WebPushCrypto.unb64url(p(ctx).getString(K_UA, "") ?: "")
    fun authSecret(ctx: Context): ByteArray = WebPushCrypto.unb64url(p(ctx).getString(K_AUTH, "") ?: "")

    /** base64url of the public key / auth — the body sent to `/napi/push/subscribe`. */
    fun p256dh(ctx: Context): String = p(ctx).getString(K_UA, "") ?: ""
    fun auth(ctx: Context): String = p(ctx).getString(K_AUTH, "") ?: ""

    fun endpoint(ctx: Context): String? = p(ctx).getString(K_ENDPOINT, null)
    fun setEndpoint(ctx: Context, url: String) = p(ctx).edit().putString(K_ENDPOINT, url).apply()

    fun clear(ctx: Context) = p(ctx).edit().clear().apply()
}
