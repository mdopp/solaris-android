package cloud.dopp.solaris.push

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push message decryption — RFC 8291 (`aes128gcm`, over RFC 8188). The server
 * (Solaris, VAPID) encrypts each push to the subscription's public key; the app
 * holds the matching private key + auth secret and decrypts here. Pure JDK crypto
 * (`java.security`/`javax.crypto`) so a round-trip is JVM-unit-testable.
 *
 * The subscription keys the app sends to `/napi/push/subscribe` are:
 * `p256dh` = base64url(uncompressed P-256 public, 65 bytes), `auth` = base64url(16
 * random bytes).
 */
object WebPushCrypto {

    private const val TAG_BITS = 128
    private val RNG = SecureRandom()

    // ---- base64url ----
    fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun unb64url(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    // ---- P-256 helpers ----
    private fun p256Params(): ECParameterSpec {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return (kpg.generateKeyPair().public as ECPublicKey).params
    }

    fun generateKeyPair(): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /** 16 random bytes used as the Web Push `auth` secret. */
    fun generateAuthSecret(): ByteArray = ByteArray(16).also { RNG.nextBytes(it) }

    /** Uncompressed SEC1 encoding of a public key: `0x04 || X(32) || Y(32)` = 65 bytes. */
    fun uncompressed(pub: ECPublicKey): ByteArray {
        val x = fixed(pub.w.affineX, 32)
        val y = fixed(pub.w.affineY, 32)
        return byteArrayOf(0x04) + x + y
    }

    fun importPublic(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "expect 65-byte uncompressed point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params())
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun fixed(bi: BigInteger, len: Int): ByteArray {
        val b = bi.toByteArray()
        return when {
            b.size == len -> b
            b.size == len + 1 && b[0] == 0.toByte() -> b.copyOfRange(1, b.size)
            b.size < len -> ByteArray(len - b.size) + b
            else -> b.copyOfRange(b.size - len, b.size)
        }
    }

    // ---- HKDF (SHA-256) ----
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm) // extract
        val t = hmac(prk, info + byteArrayOf(0x01)) // expand, one block (len ≤ 32)
        return t.copyOf(len)
    }

    private fun ecdh(priv: ECPrivateKey, pub: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(pub, true) }.generateSecret()

    /** RFC 8291 §3.4: combine auth_secret + ECDH into the 32-byte IKM. */
    private fun deriveIkm(
        authSecret: ByteArray, ecdhSecret: ByteArray, uaPublic: ByteArray, asPublic: ByteArray,
    ): ByteArray {
        val info = "WebPush: info".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00) + uaPublic + asPublic
        return hkdf(authSecret, ecdhSecret, info, 32)
    }

    private fun cek(salt: ByteArray, ikm: ByteArray): ByteArray =
        hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00), 16)

    private fun nonce(salt: ByteArray, ikm: ByteArray): ByteArray =
        hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00), 12)

    /**
     * Decrypt an `aes128gcm` Web Push [body] using the subscription's [uaPrivate] +
     * [uaPublicRaw] (65-byte uncompressed) + [authSecret] (16 bytes). Returns the
     * plaintext payload (JSON), or throws on a malformed/undecryptable body.
     */
    fun decrypt(
        body: ByteArray, uaPrivate: ECPrivateKey, uaPublicRaw: ByteArray, authSecret: ByteArray,
    ): ByteArray {
        require(body.size > 21) { "short body" }
        val salt = body.copyOfRange(0, 16)
        val idlen = body[20].toInt() and 0xff
        val asPublicRaw = body.copyOfRange(21, 21 + idlen)
        val ciphertext = body.copyOfRange(21 + idlen, body.size)

        val shared = ecdh(uaPrivate, importPublic(asPublicRaw))
        val ikm = deriveIkm(authSecret, shared, uaPublicRaw, asPublicRaw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek(salt, ikm), "AES"), GCMParameterSpec(TAG_BITS, nonce(salt, ikm)))
        val padded = cipher.doFinal(ciphertext)
        // RFC 8188 padding: content || delimiter(0x02 last record) || 0x00*
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0x00.toByte()) i--
        require(i >= 0) { "no delimiter" }
        return padded.copyOfRange(0, i) // drop the delimiter byte at i
    }

    /**
     * Encrypt [plaintext] the way the server would — **test-only**, used by the
     * round-trip unit test to validate [decrypt] without a live server. Mirrors
     * RFC 8291 with a single record.
     */
    fun encryptForTest(
        plaintext: ByteArray,
        salt: ByteArray,
        uaPublicRaw: ByteArray,
        uaPublic: ECPublicKey,
        asPrivate: ECPrivateKey,
        asPublicRaw: ByteArray,
        authSecret: ByteArray,
    ): ByteArray {
        val shared = ecdh(asPrivate, uaPublic)
        val ikm = deriveIkm(authSecret, shared, uaPublicRaw, asPublicRaw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek(salt, ikm), "AES"), GCMParameterSpec(TAG_BITS, nonce(salt, ikm)))
        val ciphertext = cipher.doFinal(plaintext + byteArrayOf(0x02)) // single record delimiter
        val rs = byteArrayOf(0x00, 0x00, 0x10, 0x00) // record size 4096 (unused by decrypt)
        return salt + rs + byteArrayOf(asPublicRaw.size.toByte()) + asPublicRaw + ciphertext
    }
}
