package hu.dkc.app

import android.net.Uri

sealed class DeepLink {
    data class Login(val uuid: String) : DeepLink()
    data class Referral(val ref: String) : DeepLink()

    companion object {
        private val HOSTS = setOf("dkc.hu", "www.dkc.hu")
        private val UUID_RE = Regex("^[A-Za-z0-9_-]{8,128}$")
        private val REF_RE = Regex("^[A-Za-z0-9_-]{1,32}$")

        fun parse(uri: Uri?): DeepLink? {
            if (uri == null) return null
            if (uri.scheme != "https" || uri.host?.lowercase() !in HOSTS) return null
            val path = uri.path?.trimEnd('/') ?: return null
            return when (path) {
                "/app/belepes" -> uri.getQueryParameter("uuid")?.trim()
                    ?.takeIf { UUID_RE.matches(it) }?.let { Login(it) }
                "/app/ajanlas" -> uri.getQueryParameter("ref")?.trim()
                    ?.takeIf { REF_RE.matches(it) }?.let { Referral(it) }
                else -> null
            }
        }

        /** Igaz, ha az URL egy /app/... deep link (akkor is, ha a paraméter hiányzik). */
        fun isAppPath(uri: Uri?): Boolean {
            if (uri == null || uri.host?.lowercase() !in HOSTS) return false
            val p = uri.path?.trimEnd('/') ?: return false
            return p == "/app/belepes" || p == "/app/ajanlas"
        }

        fun referralLink(code: String): String =
            "${BuildConfig.BASE_URL}/app/ajanlas?ref=" + Uri.encode(code)
    }
}
