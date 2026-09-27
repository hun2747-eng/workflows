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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Admin értesítés az új klíma-igényekről (https://dkc.hu/admin?tab=uj-klima):
 *  - push értesítés (FCM data üzenet: type=new_registration, count=<db>)
 *  - szám az app ikonján (az értesítés setNumber-e alapján a launcher mutatja)
 *  - tartalék: 10 percenkénti háttér-ellenőrzés az admin oldalon (AdminPollWorker), amíg él az admin munkamenet
 */
object AdminAlerts {
    const val NEW_URL = "https://dkc.hu/admin?tab=uj-klima"
    private const val CHANNEL_ID = "dkc_new_registrations"
    private const val NOTIF_ID = 4201
    private const val WORK_NAME = "dkc_admin_poll_chain"
    private const val LEGACY_PERIODIC = "dkc_admin_poll"
    private const val SP = "dkc_admin_alerts"
    private const val KEY_NOTIFIED = "notified_count"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.admin_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { setShowBadge(true) }
        nm.createNotificationChannel(ch)
    }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /**
     * Új aktuális darabszám érkezett (pushból vagy háttér-ellenőrzésből).
     * Csak akkor jelez, ha több van, mint amennyiről már szóltunk.
     */
    fun onCount(context: Context, count: Int, title: String? = null, body: String? = null, url: String? = null) {
        val notified = sp(context).getInt(KEY_NOTIFIED, 0)
        when {
            count <= 0 -> { clear(context); sp(context).edit().putInt(KEY_NOTIFIED, 0).apply() }
            count > notified -> {
                show(context, count, title, body, url)
                sp(context).edit().putInt(KEY_NOTIFIED, count).apply()
            }
            count < notified -> sp(context).edit().putInt(KEY_NOTIFIED, count).apply()
        }
    }

    /** Az admin megnézte a listát: eltűnik az értesítés és a szám az ikonról. */
    fun markSeen(context: Context, count: Int?) {
        clear(context)
        if (count != null) sp(context).edit().putInt(KEY_NOTIFIED, count).apply()
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    private fun show(context: Context, count: Int, title: String?, body: String?, url: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val target = url?.takeIf { it.startsWith("https://dkc.hu/") } ?: NEW_URL
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_URL, target)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val t = title ?: context.getString(R.string.admin_new_title)
        val b = body ?: context.resources.getQuantityString(R.plurals.admin_new_body, count, count)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(t)
            .setContentText(b)
            .setStyle(NotificationCompat.BigTextStyle().bigText(b))
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        } catch (_: SecurityException) { }
    }

    /** Ellenőrzési időköz. (A PeriodicWork minimuma 15 perc, ezért láncolt egyszeri feladatokat használunk.) */
    const val POLL_MINUTES = 10L

    fun startPolling(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_PERIODIC)
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, nextRequest(0))
    }

    /** A következő ellenőrzés beütemezése (a worker hívja a futása végén). */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, nextRequest(POLL_MINUTES))
    }

    private fun nextRequest(delayMin: Long) = OneTimeWorkRequestBuilder<AdminPollWorker>()
        .setInitialDelay(delayMin, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()

    fun stopPolling(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC)
        clear(context)
    }
}
