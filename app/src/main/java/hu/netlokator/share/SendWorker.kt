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
import java.net.URL
import java.util.concurrent.TimeUnit

class SendWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Redirect unwrapper client with followRedirects disabled so we step through 3xx Location headers
    private val redirectClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun doWork(): Result {
        val rawUrl = inputData.getString("TARGET_URL") ?: return Result.failure()

        val prefs = context.getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")?.trimEnd('/') ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""

        val targetUrl = resolveOriginalUrl(rawUrl)

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            HistoryManager.addSubmission(context, targetUrl, false)
            showNotification("NetLokátor hiba", "Hiányzó API-kulcs vagy alap URL. Nyisd meg az alkalmazást.")
            return Result.failure()
        }

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
            apiClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                val serverMsg = try {
                    JSONObject(respBody).optString("message", "Sikeres beküldés.")
                } catch (e: Exception) {
                    if (response.isSuccessful) "Sikeres beküldés." else "Hiba: HTTP ${response.code}"
                }

                if (response.isSuccessful) {
                    HistoryManager.addSubmission(context, targetUrl, true)
                    showNotification("NetLokátor", serverMsg + "\n" + targetUrl)
                    Result.success()
                } else {
                    HistoryManager.addSubmission(context, targetUrl, false)
                    showNotification("NetLokátor hiba", serverMsg + " (" + response.code + ")\n" + targetUrl)
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            HistoryManager.addSubmission(context, targetUrl, false)
            val errText = e.localizedMessage ?: "Nem érhető el a szerver."
            showNotification("NetLokátor kapcsolati hiba", errText + "\n" + targetUrl)
            Result.retry()
        }
    }

    private fun resolveOriginalUrl(initialUrl: String): String {
        var currentUrl = initialUrl.trim()
        currentUrl = extractNestedParam(currentUrl)

        var step = 0
        while (step < 12) {
            step++
            try {
                val req = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val nextUrl = redirectClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if (code in 300..399) {
                        val loc = resp.header("Location")
                        if (!loc.isNullOrEmpty()) {
                            try {
                                URL(URL(currentUrl), loc).toString()
                            } catch (_: Exception) {
                                loc
                            }
                        } else null
                    } else {
                        null
                    }
                }

                if (nextUrl != null && nextUrl != currentUrl) {
                    currentUrl = extractNestedParam(nextUrl)
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }

        return currentUrl
    }

    private fun extractNestedParam(url: String): String {
        try {
            val uri = Uri.parse(url)
            val embedded = uri.getQueryParameter("url")
                ?: uri.getQueryParameter("q")
                ?: uri.getQueryParameter("target")
                ?: uri.getQueryParameter("dest")
            if (!embedded.isNullOrEmpty() && (embedded.startsWith("http://") || embedded.startsWith("https://"))) {
                return extractNestedParam(embedded)
            }
        } catch (_: Exception) {}
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
