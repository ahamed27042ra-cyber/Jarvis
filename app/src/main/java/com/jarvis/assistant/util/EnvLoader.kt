package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.JarvisApplication

/**
 * Loads API credentials from encrypted local storage or Firebase Remote Config.
 */
object EnvLoader {
    private var cachedApiKey: String? = null
    private var cachedYoutubeKey: String? = null
    fun resetCache() {
        cachedApiKey = null
        cachedYoutubeKey = null
    }

    fun getApiKey(context: Context): String {
        cachedApiKey?.takeIf { it.isNotBlank() }?.let { return it }

        val prefsKey = com.jarvis.assistant.security.EncryptedPrefsManager
            .getOrMigrateSecureString(context, JarvisApplication.PREFS_NAME, "api_key")
            .trim()
        if (prefsKey.isNotEmpty()) {
            cachedApiKey = prefsKey
            return prefsKey
        }

        // Do not load Gemini credentials from APK assets: anything bundled in an APK
        // can be extracted. Users must provide a key, which is stored encrypted above.
        return ""
    }

    fun getYoutubeApiKey(context: Context): String {
        cachedYoutubeKey?.let { return it }

        // 1. Check Firebase Remote Config backend first for secure key
        val firebaseKey = com.jarvis.assistant.firebase.FirebaseManager.getYoutubeApiKey()
        if (firebaseKey.isNotBlank()) {
            cachedYoutubeKey = firebaseKey
            return firebaseKey
        }

        // Never package API credentials in APK assets; they can be extracted from the APK.
        // If Remote Config is not configured, leave the key empty and use keyless fallbacks.
        return ""
    }
}