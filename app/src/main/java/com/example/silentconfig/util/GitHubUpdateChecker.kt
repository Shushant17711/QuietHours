package com.example.aquiethours.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val newVersion: String,
    val downloadUrl: String
)

object GitHubUpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://api.github.com/repos/Shushant17711/QuietHours/releases/latest"

    /**
     * Checks the latest GitHub release. If the tag name (e.g. "v1.1.0") is different/higher
     * than the current version (e.g. "1.0"), returns an UpdateInfo. Otherwise, returns null.
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name") // e.g. "v1.0.0"
                val htmlUrl = json.getString("html_url") // e.g. "https://github.com/.../releases/tag/v1.0.0"

                // Strip 'v' from tag if present
                val latestVersion = tagName.removePrefix("v")
                val cleanCurrent = currentVersion.removePrefix("v")

                if (latestVersion != cleanCurrent && isNewer(cleanCurrent, latestVersion)) {
                    return@withContext UpdateInfo(tagName, htmlUrl)
                }
            } else {
                Log.e(TAG, "Failed to check update. Code: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
        return@withContext null
    }

    private fun isNewer(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false // Identical
    }
}
