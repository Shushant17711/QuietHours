package com.example.aquiethours.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "aquiethours_settings", Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_API_KEY = "openrouter_api_key"
        private const val KEY_MODEL = "openrouter_model"
        private const val DEFAULT_MODEL = "google/gemini-2.5-flash"
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
}
