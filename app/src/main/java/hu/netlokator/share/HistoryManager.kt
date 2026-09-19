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
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val currentArray = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        val newItem = JSONObject().apply {
            put("url", url)
            put("timestamp", System.currentTimeMillis())
            put("success", success)
        }

        val updatedArray = JSONArray()
        updatedArray.put(newItem)
        for (i in 0 until minOf(currentArray.length(), 150)) {
            try {
                val existing = currentArray.getJSONObject(i)
                if (existing.optString("url") != url || (System.currentTimeMillis() - existing.optLong("timestamp") > 2000)) {
                    updatedArray.put(existing)
                }
            } catch (_: Exception) {}
        }

        prefs.edit().putString(PREF_KEY, updatedArray.toString()).apply()
    }

    fun getSubmissions(context: Context): List<SubmissionItem> {
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

        val list = mutableListOf<SubmissionItem>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                list.add(
                    SubmissionItem(
                        url = obj.optString("url"),
                        timestamp = obj.optLong("timestamp"),
                        success = obj.optBoolean("success", true)
                    )
                )
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun clearSubmissions(context: Context) {
        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_KEY).apply()
    }
}
