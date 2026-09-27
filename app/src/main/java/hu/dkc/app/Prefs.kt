package hu.dkc.app

import android.content.Context
import android.content.SharedPreferences

/** Egyszerű, app-privát tároló a belépési tokenhez, ajánlói kódhoz és push állapothoz. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dkc_prefs", Context.MODE_PRIVATE)

    /** customers.app_uuid – minden API hívásnál: Authorization: Bearer <token> */
    var token: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(v) = sp.edit().apply { if (v == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, v) }.apply()

    /** Ajánlói kód a /app/ajanlas?ref=<kód> linkből – regisztrációkor referral_code mezőben megy el. */
    var referralCode: String?
        get() = sp.getString(KEY_REF, null)
        set(v) = sp.edit().apply { if (v == null) remove(KEY_REF) else putString(KEY_REF, v) }.apply()

    var fcmToken: String?
        get() = sp.getString(KEY_FCM, null)
        set(v) = sp.edit().putString(KEY_FCM, v).apply()

    /** Melyik FCM token + app token párost küldtük már el a szervernek. */
    var fcmTokenSentFor: String?
        get() = sp.getString(KEY_FCM_SENT, null)
        set(v) = sp.edit().putString(KEY_FCM_SENT, v).apply()

    var pushConsent: Boolean
        get() = sp.getBoolean(KEY_CONSENT, false)
        set(v) = sp.edit().putBoolean(KEY_CONSENT, v).apply()

    var pushConsentAsked: Boolean
        get() = sp.getBoolean(KEY_CONSENT_ASKED, false)
        set(v) = sp.edit().putBoolean(KEY_CONSENT_ASKED, v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    fun logout() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_FCM_SENT).remove(KEY_CONSENT)
            .remove(KEY_CONSENT_ASKED).apply()
    }

    companion object {
        private const val KEY_TOKEN = "app_uuid"
        private const val KEY_REF = "referral_code"
        private const val KEY_FCM = "fcm_token"
        private const val KEY_FCM_SENT = "fcm_token_sent_for"
        private const val KEY_CONSENT = "push_consent"
        private const val KEY_CONSENT_ASKED = "push_consent_asked"
    }
}
