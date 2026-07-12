package cloud.dopp.solaris.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Encrypted-at-rest storage for the native client's device-token bearer
 * (`sol_device_…`), backed by [EncryptedSharedPreferences] + an AndroidKeyStore
 * master key.
 *
 * Resilient by design: if the keyset can't be decrypted — e.g. after a
 * backup-restore where the encrypted file was restored but the AndroidKeyStore
 * master key was not (`AEADBadTagException`) — it wipes the corrupt file and
 * recreates a fresh keyset instead of crashing. Every accessor is exception-safe.
 */
object TokenStore {
    private const val PREFS = "solaris_secure_prefs"
    private const val KEY_TOKEN = "device_token"

    private fun encrypted(ctx: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            ctx.applicationContext,
            PREFS,
            MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun prefs(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        return try {
            encrypted(app)
        } catch (e: Throwable) {
            // Undecryptable keyset — drop it and start fresh (the token is
            // re-mintable via pairing, so losing it is acceptable; crashing is not).
            wipe(app)
            try {
                encrypted(app)
            } catch (e2: Throwable) {
                app.getSharedPreferences("${PREFS}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun wipe(ctx: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ctx.deleteSharedPreferences(PREFS)
            File(ctx.applicationInfo.dataDir, "shared_prefs/$PREFS.xml").delete()
        }
    }

    fun save(ctx: Context, token: String) {
        runCatching { prefs(ctx).edit().putString(KEY_TOKEN, token).apply() }
    }

    fun get(ctx: Context): String? =
        runCatching { prefs(ctx).getString(KEY_TOKEN, null) }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { prefs(ctx).edit().remove(KEY_TOKEN).apply() }
    }

    fun isPaired(ctx: Context): Boolean = !get(ctx).isNullOrBlank()
}
