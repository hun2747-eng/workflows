package hu.netlokator.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SendWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val rawUrl = inputData.getString("TARGET_URL") ?: return Result.failure()

        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            showNotification("NetLokátor hiba", "Hiányzó API-kulcs vagy alap URL. Nyisd meg az alkalmazást.")
            return Result.failure()
        }

        val targetUrl = resolveOriginalUrl(rawUrl)

        val endpoint = "$baseUrl/api/extension/index-url"
        val payload = JSONObject().apply { put("url", targetUrl) }.toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", apiKey)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                val serverMsg = try {
                    JSONObject(respBody).optString("message", "Sikeres beküldés.")
                } catch (e: Exception) {
                    if (response.isSuccessful) "Sikeres beküldés." else "Hiba: HTTP ${response.code}"
                }

                if (response.isSuccessful) {
                    HistoryManager.addSubmission(context, targetUrl, true)
                    showNotification("NetLokátor", "$serverMsg\n$targetUrl")
                    Result.success()
                } else {
                    HistoryManager.addSubmission(context, targetUrl, false)
                    showNotification("NetLokátor hiba", "$serverMsg (${response.code})")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            showNotification("NetLokátor kapcsolati hiba", e.localizedMessage ?: "Nem érhető el a szerver.")
            Result.retry()
        }
    }

    private fun resolveOriginalUrl(url: String): String {
        try {
            val uri = Uri.parse(url)
            val embedded = uri.getQueryParameter("url") ?: uri.getQueryParameter("q")
            if (!embedded.isNullOrEmpty() && (embedded.startsWith("http://") || embedded.startsWith("https://"))) {
                return resolveOriginalUrl(embedded)
            }
        } catch (_: Exception) {}

        if (url.contains("share.google") || url.contains("goo.gl") || url.contains("t.co") || url.contains("bit.ly")) {
            try {
                val headReq = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build()

                client.newCall(headReq).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (finalUrl.isNotEmpty() && !finalUrl.contains("share.google")) {
                        return finalUrl
                    }
                }

                val getReq = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build()

                client.newCall(getReq).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (finalUrl.isNotEmpty() && !finalUrl.contains("share.google")) {
                        return finalUrl
                    }
                }
            } catch (_: Exception) {}
        }

        return url
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "netlokator_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "NetLokátor értesítések",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
