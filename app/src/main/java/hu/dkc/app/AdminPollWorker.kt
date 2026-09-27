package hu.dkc.app

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

/**
 * Háttér-ellenőrzés (15 percenként): az admin munkamenet sütijével lekéri az admin oldalt,
 * és kiolvassa az "Új klímát szeretne N" számot. Ha a munkamenet lejárt (belépő oldal jön), nem csinál semmit.
 */
class AdminPollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val cookie = try { CookieManager.getInstance().getCookie(AdminAlerts.NEW_URL) } catch (_: Exception) { null }
        if (cookie.isNullOrBlank()) return Result.success()
        return try {
            val c = (URL(AdminAlerts.NEW_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", "DKCApp/${BuildConfig.VERSION_NAME} (Android; poll)")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = c.responseCode
            if (code != 200) { c.disconnect(); return Result.success() }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            parseCount(html)?.let { AdminAlerts.onCount(applicationContext, it) }
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    companion object {
        private val TAGS = Regex("<[^>]*>")
        private val COUNT = Regex("Új klímát szeretne\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun parseCount(html: String): Int? {
            if (html.contains("name=\"password\"") && html.contains("elfelejtett-jelszo")) return null // belépő oldal
            val text = html.replace(TAGS, " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
            return COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
