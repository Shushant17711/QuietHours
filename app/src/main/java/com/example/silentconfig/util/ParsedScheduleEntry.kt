package com.example.aquiethours.util

import android.util.Log
import com.example.aquiethours.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek

data class ParsedScheduleEntry(
    val day: DayOfWeek,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val label: String
)

object AiScheduleParser {

    private const val TAG = "AiScheduleParser"
    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

    suspend fun parseTimetable(
        ocrText: String,
        profiles: List<Profile>,
        apiKey: String,
        model: String,
        base64Image: String? = null
    ): List<ParsedScheduleEntry> = withContext(Dispatchers.IO) {

        val systemPrompt = """You are a timetable parser. The user will give you an image of a timetable or OCR text.
Your job is to extract all class/schedule entries and return them as a JSON array.

Each entry must have these fields:
- "day": The day of the week as a string, one of: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
- "startHour": Integer (0-23, 24-hour format)
- "startMinute": Integer (0-59)
- "endHour": Integer (0-23, 24-hour format)
- "endMinute": Integer (0-59)
- "label": String (the class/subject name, e.g. "Mathematics", "Physics Lab")

Important rules:
- CRITICAL: Pay very close attention to the table column headers (e.g., Mon, Tue, Wed).
- If a column for a specific day is completely empty, there are NO classes for that day. 
- Do NOT shift classes to the left to fill empty columns! A class under "Tue" MUST be assigned to TUESDAY, even if "Mon" is empty.
- Convert any 12-hour times (AM/PM) to 24-hour format.
- If a time range like "9:00-10:00" is given, startHour=9, startMinute=0, endHour=10, endMinute=0.
- If only a start time is given with a duration (e.g., "1 hour"), calculate the end time.
- If no end time or duration is given, assume 1-hour duration.
- Extract the subject/class name as the label.
- Parse ALL entries you can find in the timetable.
- Return ONLY the JSON array, no other text, no markdown formatting, no code blocks.

Example output:
[{"day":"MONDAY","startHour":9,"startMinute":0,"endHour":10,"endMinute":0,"label":"Mathematics"}]"""

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            
            val userContent = if (base64Image != null) {
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Here is my timetable image. Please extract all schedule entries.")
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                }
            } else {
                "Here is the OCR text from my timetable. Please extract all schedule entries:\n\n$ocrText"
            }
            
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.1)
            put("max_tokens", 4096)
        }

        val url = URL(API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", "https://aquiethours.app")
            connection.setRequestProperty("X-Title", "AQuietHours Timetable Parser")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "API error $responseCode: $errorBody")
                throw Exception("API error ($responseCode): $errorBody")
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            Log.d(TAG, "AI response: $response")

            val responseJson = JSONObject(response)
            val choices = responseJson.getJSONArray("choices")
            if (choices.length() == 0) {
                throw Exception("No response from AI")
            }

            var content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Strip markdown code blocks if present
            if (content.startsWith("```")) {
                content = content.removePrefix("```json").removePrefix("```")
                content = content.removeSuffix("```").trim()
            }

            parseJsonResponse(content)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonResponse(json: String): List<ParsedScheduleEntry> {
        val entries = mutableListOf<ParsedScheduleEntry>()
        val jsonArray = JSONArray(json)

        for (i in 0 until jsonArray.length()) {
            try {
                val obj = jsonArray.getJSONObject(i)
                val dayStr = obj.getString("day").uppercase()
                val day = try {
                    DayOfWeek.valueOf(dayStr)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipping invalid day: $dayStr")
                    continue
                }

                entries.add(
                    ParsedScheduleEntry(
                        day = day,
                        startHour = obj.getInt("startHour").coerceIn(0, 23),
                        startMinute = obj.getInt("startMinute").coerceIn(0, 59),
                        endHour = obj.getInt("endHour").coerceIn(0, 23),
                        endMinute = obj.getInt("endMinute").coerceIn(0, 59),
                        label = obj.optString("label", "Class")
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed entry at index $i", e)
            }
        }

        return entries
    }

    suspend fun testConnection(apiKey: String, model: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with just the word OK")
                })
            }

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("max_tokens", 10)
            }

            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }
}
