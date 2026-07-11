package cloud.dopp.solaris.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for the native client's device-token bearer
 * (`sol_device_…`). Backed by [EncryptedSharedPreferences] with an
 * AndroidKeyStore master key, so the token never sits in plaintext prefs.
 *
 * The token authenticates the widgets/tiles against the Solaris API as the
 * resident who minted it (see docs/CONTRACT.md (b)).
 */
object TokenStore {
    private const val PREFS = "solaris_secure_prefs"
    private const val KEY_TOKEN = "device_token"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx.applicationContext,
        PREFS,
        MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(ctx: Context, token: String) =
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()

    fun get(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY_TOKEN).apply()

    fun isPaired(ctx: Context): Boolean = !get(ctx).isNullOrBlank()
}
