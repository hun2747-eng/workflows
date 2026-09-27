package hu.dkc.app

import android.content.Context
import android.webkit.CookieManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

/**
 * Háttér-ellenőrzés (10 percenként): az admin munkamenet sütijével lekéri az admin oldalt,
 * kiolvassa az "Új klímát szeretne N" számot és a következő 20 nap (zöld) karbantartásait. Ha a munkamenet lejárt (belépő oldal jön), nem csinál semmit.
 */
class AdminPollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val admin = applicationContext.getSharedPreferences("dkc_prefs", Context.MODE_PRIVATE).getBoolean("admin_logged_in", false)
        if (!admin) return Result.success()
        try { check() } finally { AdminAlerts.scheduleNext(applicationContext) }
        return Result.success()
    }

    private fun check() {
        fetch(AdminAlerts.NEW_URL)?.let { html -> parseCount(html)?.let { AdminAlerts.onCount(applicationContext, it) } }
        fetch(Maintenance.URL)?.let { html -> Maintenance.parse(applicationContext, html)?.let { Maintenance.onRows(applicationContext, it) } }
    }

    /** Admin oldal lekérése a WebView sütijével; null, ha nincs süti / hiba / nem 200. */
    private fun fetch(url: String): String? {
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        if (cookie.isNullOrBlank()) return null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", "DKCApp/${BuildConfig.VERSION_NAME} (Android; poll)")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = c.responseCode
            val html = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            c.disconnect()
            html
        } catch (_: Exception) { null }
    }

    companion object {
        private val TAGS = Regex("<[^>]*>")
        private val COUNT = Regex("Új klímát szeretne\\s*(\\d+)", RegexOption.IGNORE_CASE)

        fun isLoginPage(html: String) = html.contains("name=\"password\"") && html.contains("elfelejtett-jelszo")

        fun parseCount(html: String): Int? {
            if (isLoginPage(html)) return null
            val text = html.replace(TAGS, " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
            return COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
