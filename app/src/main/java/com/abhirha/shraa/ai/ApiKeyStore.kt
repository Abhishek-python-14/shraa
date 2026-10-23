package com.abhirha.shraa.ai

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys (one per provider), encrypted on the device (Android Keystore).
 * Keys are typed by the owner in Settings; nothing is hardcoded in the source.
 */
class ApiKeyStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        // EncryptedSharedPreferences needs API 23+; older phones fall back to app-private prefs.
        if (Build.VERSION.SDK_INT < 23) {
            appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        } else {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext, FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun save(provider: AiProvider, key: String) {
        prefs.edit().putString(provider.keyPrefName, clean(key)).apply()
    }

    fun get(provider: AiProvider): String? =
        prefs.getString(provider.keyPrefName, null)?.let(::clean)?.takeIf { it.isNotBlank() }

    /** ZenMux and FreeLLMAPI were removed from the app: delete any key still saved for them. */
    fun purgeRemovedProviders() {
        prefs.edit().remove("zenmux_api_key").remove("freellmapi_api_key").apply()
    }

    fun clear(provider: AiProvider) {
        prefs.edit().remove(provider.keyPrefName).apply()
    }

    companion object {
        private const val FILE = "shraa_secure_prefs"

        /** Visible ASCII only, so hidden/zero-width characters from copy-paste never break a key. */
        fun clean(key: String): String = key.filter { it.code in 33..126 && it != '"' && it != '\'' }
    }
}
