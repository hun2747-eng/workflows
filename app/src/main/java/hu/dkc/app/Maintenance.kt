package hu.dkc.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Esedékes karbantartások (https://dkc.hu/admin/?szures=kov-20, zöld sorok):
 *  - szám a főképernyő widgeten (MaintenanceWidget)
 *  - értesítés, ha újabb ügyfél kerül a listába
 */
object Maintenance {
    const val URL = "https://dkc.hu/admin/?szures=kov-20"
    private const val CHANNEL_ID = "dkc_maintenance"
    private const val NOTIF_ID = 4202
    private const val SP = "dkc_maintenance"
    private const val KEY_COUNT = "count"
    private const val KEY_KEYS = "keys"
    private const val KEY_INIT = "initialized"
    private const val KEY_GREEN = "green_token"

    data class Row(val date: String, val name: String) {
        val key get() = "$date|$name"
    }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** -1 = ismeretlen (nincs admin / még nem volt adat) */
    fun count(c: Context): Int = sp(c).getInt(KEY_COUNT, -1)

    /** Az appban (WebView) megállapított "zöld" CSS osztály – a háttér-ellenőrzés ezt keresi a HTML-ben. */
    fun learnGreenToken(c: Context, token: String?) {
        if (!token.isNullOrBlank() && token.length < 80) sp(c).edit().putString(KEY_GREEN, token).apply()
    }

    private val TR = Regex("<tr\\b([^>]*)>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TD = Regex("<td\\b([^>]*)>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAGS = Regex("<[^>]*>")
    private val WS = Regex("\\s+")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val GREEN = Regex(
        "bg-(green|emerald|lime)-|table-success|\\bsuccess\\b|background(-color)?\\s*:\\s*(#(bbf7d0|dcfce7|d1fae5|a7f3d0|86efac|b9f6ca|c8e6c9|d4edda|c3e6cb)|(light)?green)",
        RegexOption.IGNORE_CASE,
    )

    private fun text(html: String) = html.replace(TAGS, " ").replace("&nbsp;", " ")
        .replace("&amp;", "&").replace(WS, " ").trim()

    /** A lista zöld sorai; null, ha belépő oldal jött vagy nincs táblázat. */
    fun parse(c: Context, html: String): List<Row>? {
        if (AdminPollWorker.isLoginPage(html)) return null
        val learned = sp(c).getString(KEY_GREEN, null)
        val rows = ArrayList<Row>()
        var anyDataRow = false
        for (m in TR.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val tds = TD.findAll(inner).toList()
            if (tds.size < 3) continue
            val cells = tds.map { text(it.groupValues[2]) }
            val dateIdx = cells.indexOfFirst { DATE.containsMatchIn(it) }
            if (dateIdx < 0) continue
            anyDataRow = true
            val styleSrc = attrs + " " + tds.joinToString(" ") { it.groupValues[1] }
            val green = (learned != null && styleSrc.contains(learned)) || GREEN.containsMatchIn(styleSrc)
            if (!green) continue
            val date = DATE.find(cells[dateIdx])!!.value
            val name = cells.getOrNull(dateIdx + 1).orEmpty()
            rows.add(Row(date, name))
        }
        return if (anyDataRow) rows else null
    }

    /** Új állapot: widget frissítése + értesítés az újonnan megjelent ügyfelekről. */
    fun onRows(c: Context, rows: List<Row>) {
        val p = sp(c)
        val old = p.getString(KEY_KEYS, null)?.let { s ->
            val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }.toSet()
        } ?: emptySet()
        val initialized = p.getBoolean(KEY_INIT, false)
        val newRows = rows.filter { it.key !in old }
        p.edit()
            .putInt(KEY_COUNT, rows.size)
            .putString(KEY_KEYS, JSONArray(rows.map { it.key }).toString())
            .putBoolean(KEY_INIT, true)
            .apply()
        MaintenanceWidget.updateAll(c)
        if (initialized && newRows.isNotEmpty()) notifyNew(c, newRows, rows.size)
    }

    /** Csak a darabszám ismert (az appban megnyitott oldalról). */
    fun onCount(c: Context, count: Int) {
        sp(c).edit().putInt(KEY_COUNT, count).apply()
        MaintenanceWidget.updateAll(c)
    }

    fun reset(c: Context) {
        sp(c).edit().remove(KEY_COUNT).remove(KEY_KEYS).remove(KEY_INIT).apply()
        NotificationManagerCompat.from(c).cancel(NOTIF_ID)
        MaintenanceWidget.updateAll(c)
    }

    fun clearNotification(c: Context) = NotificationManagerCompat.from(c).cancel(NOTIF_ID)

    private fun notifyNew(c: Context, newRows: List<Row>, total: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, c.getString(R.string.maint_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) }
        )
        val intent = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_URL, URL)
        }
        val pi = PendingIntent.getActivity(c, NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (newRows.size == 1) c.getString(R.string.maint_new_title_one)
        else c.getString(R.string.maint_new_title_many, newRows.size)
        val lines = newRows.take(6).joinToString("\n") { "${it.date} – ${it.name}" } +
            (if (newRows.size > 6) "\n…" else "") + "\n" + c.getString(R.string.maint_total, total)
        val n = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(c, R.color.maint_green_dark))
            .setContentTitle(title)
            .setContentText(newRows.first().let { "${it.date} – ${it.name}" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }
}
