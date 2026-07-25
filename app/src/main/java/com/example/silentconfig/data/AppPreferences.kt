package com.example.aquiethours.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isGlobalEnabled(): Boolean {
        return prefs.getBoolean(KEY_GLOBAL_ENABLED, true)
    }

    fun setGlobalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "aquiethours_prefs"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        
        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
