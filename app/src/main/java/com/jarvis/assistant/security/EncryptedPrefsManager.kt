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

    /**
     * Reads an encrypted value first. If absent, migrates a legacy value from the named
     * preferences file and removes the plaintext copy only after the encrypted write succeeds.
     * If secure storage is unavailable, the exception is intentionally propagated (fail closed).
     */
    fun getOrMigrateSecureString(
        context: Context,
        legacyPreferencesName: String,
        key: String,
        defaultValue: String = ""
    ): String {
        val secureValue = getSecureString(context, key, "")
        if (secureValue.isNotBlank()) return secureValue

        val legacyPrefs = context.getSharedPreferences(legacyPreferencesName, Context.MODE_PRIVATE)
        val legacyValue = legacyPrefs.getString(key, null)?.trim()
        if (legacyValue.isNullOrBlank()) return defaultValue

        // Migration must reach disk before deleting the only legacy copy. apply() is
        // asynchronous, so use commit() here and fail closed if persistence is unsuccessful.
        val persisted = getSecurePreferences(context).edit().putString(key, legacyValue).commit()
        if (!persisted) {
            throw IllegalStateException("Could not persist migrated credential securely")
        }
        legacyPrefs.edit().remove(key).commit()
        return legacyValue
    }

    fun putSecureString(context: Context, key: String, value: String) {
        val persisted = getSecurePreferences(context).edit().putString(key, value).commit()
        if (!persisted) {
            throw IllegalStateException("Could not persist credential securely")
        }
    }

    fun getSecureString(context: Context, key: String, defaultValue: String = ""): String {
        return getSecurePreferences(context).getString(key, defaultValue) ?: defaultValue
    }

    fun removeSecureKey(context: Context, key: String) {
        getSecurePreferences(context).edit().remove(key).apply()
    }
}
