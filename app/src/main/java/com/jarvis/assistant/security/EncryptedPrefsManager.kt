package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted preferences manager backed by Android KeyStore AES-256 GCM encryption.
 * Encrypts API keys, user session tokens, and sensitive application credentials.
 */
object EncryptedPrefsManager {

    private const val TAG = "EncryptedPrefsManager"
    private const val ENCRYPTED_PREFS_FILENAME = "jarvis_secure_prefs"

    private fun getSecurePreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fail closed: do not silently downgrade credentials to unencrypted storage.
            Log.e(TAG, "Encrypted preferences unavailable; refusing plaintext fallback", e)
            throw IllegalStateException("JARVIS secure storage is unavailable on this device", e)
        }
    }

    fun putSecureString(context: Context, key: String, value: String) {
        getSecurePreferences(context).edit().putString(key, value).apply()
    }

    fun getSecureString(context: Context, key: String, defaultValue: String = ""): String {
        return getSecurePreferences(context).getString(key, defaultValue) ?: defaultValue
    }

    fun removeSecureKey(context: Context, key: String) {
        getSecurePreferences(context).edit().remove(key).apply()
    }
}
