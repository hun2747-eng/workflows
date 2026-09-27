package hu.dkc.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

object PushHelper {
    private const val TAG = "DKCPush"

    fun isAvailable(context: Context): Boolean =
        BuildConfig.FCM_ENABLED && FirebaseApp.getApps(context).isNotEmpty()

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            context.getString(R.string.notif_channel_id),
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(ch)
    }

    /** Lekéri az FCM tokent és (ha be van jelentkezve) elküldi a szervernek. */
    fun syncToken(context: Context) {
        if (!isAvailable(context)) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                Prefs(context).fcmToken = t
                sendTokenIfNeeded(context)
            }.addOnFailureListener { Log.w(TAG, "FCM token hiba", it) }
        } catch (e: Exception) {
            Log.w(TAG, "FCM nem elérhető", e)
        }
    }

    fun sendTokenIfNeeded(context: Context, force: Boolean = false) {
        val prefs = Prefs(context)
        val appToken = prefs.token ?: return
        val fcm = prefs.fcmToken ?: return
        val key = "$appToken|$fcm"
        if (!force && prefs.fcmTokenSentFor == key) return
        val body = JSONObject().put("token", fcm).put("platform", "android")
        Api.call(context, "POST", "/api/app/push-token", body) { r ->
            if (r.ok) prefs.fcmTokenSentFor = key else Log.w(TAG, "push-token: ${r.code} ${r.body}")
        }
    }

    fun sendConsent(context: Context, consent: Boolean, cb: ((Boolean) -> Unit)? = null) {
        Api.call(context, "POST", "/api/app/push-consent", JSONObject().put("consent", consent)) { r ->
            if (r.ok) Prefs(context).pushConsent = consent
            cb?.invoke(r.ok)
        }
    }
}
