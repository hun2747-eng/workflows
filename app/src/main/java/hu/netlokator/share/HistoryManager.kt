package hu.netlokator.share

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SubmissionItem(
    val url: String,
    val timestamp: Long,
    val success: Boolean
)

object HistoryManager {
    private const val PREF_KEY = "submitted_links"

    @Synchronized
    fun addSubmission(context: Context, url: String, success: Boolean = true) {
        if (url.isBlank()) return
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val currentArray = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        val now = System.currentTimeMillis()
        val newItem = JSONObject().apply {
            put("url", url.trim())
            put("timestamp", now)
            put("success", success)
        }

        val updatedArray = JSONArray()
        updatedArray.put(newItem)
        for (i in 0 until minOf(currentArray.length(), 200)) {
            try {
                val existing = currentArray.getJSONObject(i)
                val existingUrl = existing.optString("url", "")
                val existingTime = existing.optLong("timestamp", 0)
                // Deduplicate identical URL submitted within 3 seconds
                if (existingUrl != url.trim() || (now - existingTime > 3000)) {
                    updatedArray.put(existing)
                }
            } catch (_: Exception) {}
        }

        prefs.edit().putString(PREF_KEY, updatedArray.toString()).commit()
    }

    @Synchronized
    fun getSubmissions(context: Context): List<SubmissionItem> {
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        val list = mutableListOf<SubmissionItem>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", "")
                if (url.isNotEmpty()) {
                    list.add(
                        SubmissionItem(
                            url = url,
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            success = obj.optBoolean("success", true)
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun clearSubmissions(context: Context) {
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_KEY).commit()
    }
}
