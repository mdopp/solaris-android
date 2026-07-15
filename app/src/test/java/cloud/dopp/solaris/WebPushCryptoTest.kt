package cloud.dopp.solaris

import cloud.dopp.solaris.push.WebPushCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Validates the RFC 8291 Web Push decryption (#push) without a live server: encrypt
 * a payload the way the VAPID server would (to the app's subscription key), then
 * decrypt with the app's private key and assert it round-trips. Exercises the full
 * path — ECDH, the auth/HKDF derivation, AES-128-GCM, and RFC 8188 padding.
 * Robolectric only for `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebPushCryptoTest {

    @Test
    fun encryptThenDecryptRoundTrips() {
        // The app's subscription keypair (ua = user agent) + auth secret.
        val ua = WebPushCrypto.generateKeyPair()
        val uaPub = ua.public as ECPublicKey
        val uaPriv = ua.private as ECPrivateKey
        val uaRaw = WebPushCrypto.uncompressed(uaPub)
        val auth = WebPushCrypto.generateAuthSecret()

        // The server's ephemeral keypair (as = application server) for this message.
        val server = WebPushCrypto.generateKeyPair()
        val asPriv = server.private as ECPrivateKey
        val asRaw = WebPushCrypto.uncompressed(server.public as ECPublicKey)

        val salt = ByteArray(16) { it.toByte() }
        val plaintext = """{"title":"Neue Freigabe","body":"Update nginx?","data":{"kind":"servicebay","id":"a13b382c"}}"""
            .toByteArray(Charsets.UTF_8)

        val body = WebPushCrypto.encryptForTest(plaintext, salt, uaRaw, uaPub, asPriv, asRaw, auth)
        val out = WebPushCrypto.decrypt(body, uaPriv, uaRaw, auth)

        assertArrayEquals(plaintext, out)
    }

    @Test
    fun uncompressedPointIs65Bytes() {
        val kp = WebPushCrypto.generateKeyPair()
        val raw = WebPushCrypto.uncompressed(kp.public as ECPublicKey)
        assertEquals(65, raw.size)
        assertEquals(0x04.toByte(), raw[0])
        // Import round-trips to the same encoding.
        assertArrayEquals(raw, WebPushCrypto.uncompressed(WebPushCrypto.importPublic(raw)))
    }
}
