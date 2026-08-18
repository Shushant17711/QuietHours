package com.example.aquiethours.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsManager private constructor(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context).also {
        migrateLegacyPrefs(context, it)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Should only happen if the Keystore is unavailable/corrupted on the device.
            // Fall back to plain prefs rather than losing the API key entirely.
            Log.e("SettingsManager", "Failed to create encrypted prefs, falling back to plain storage", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Moves any API key/model saved before encryption was added into the encrypted store,
    // then wipes the plaintext copy. No-op once migrated (legacy file will be empty).
    private fun migrateLegacyPrefs(context: Context, target: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyKey = legacyPrefs.getString(KEY_API_KEY, null)
        val legacyModel = legacyPrefs.getString(KEY_MODEL, null)
        if (legacyKey == null && legacyModel == null) return

        target.edit().apply {
            legacyKey?.let { putString(KEY_API_KEY, it) }
            legacyModel?.let { putString(KEY_MODEL, it) }
        }.apply()
        legacyPrefs.edit().clear().apply()
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getModel(): String {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun isApiKeyConfigured(): Boolean {
        return getApiKey().isNotBlank()
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "aquiethours_settings"
        private const val PREFS_NAME = "aquiethours_settings_secure"
        private const val KEY_API_KEY = "openrouter_api_key"
        private const val KEY_MODEL = "openrouter_model"
        private const val DEFAULT_MODEL = "google/gemini-2.5-flash"

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
