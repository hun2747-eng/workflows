package hu.dkc.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/** A "Fiókom" fül: regisztrációs űrlap (kijelentkezve) vagy ügyfél-adatlap (bejelentkezve). */
class AccountPane(private val activity: MainActivity, private val container: FrameLayout) {

    private val prefs = Prefs(activity)
    private var mode: String? = null          // "register" | "account"
    private var registerView: View? = null
    private var accountView: View? = null
    private var customer: JSONObject? = null
    private var loading = false

    fun render(forceReload: Boolean = false) {
        if (prefs.isLoggedIn) showAccount(forceReload) else showRegister()
    }

    // ================================================================ REGISZTRÁCIÓ

    private fun showRegister() {
        val v = registerView ?: LayoutInflater.from(activity)
            .inflate(R.layout.view_register, container, false).also { setupRegister(it); registerView = it }
        if (mode != "register") {
            container.removeAllViews(); container.addView(v); mode = "register"
        }
        val ref = prefs.referralCode
        v.findViewById<View>(R.id.refBanner).isVisible = !ref.isNullOrBlank()
        v.findViewById<TextView>(R.id.refBannerText).text = activity.getString(R.string.ref_banner, ref ?: "")
        val refField = v.findViewById<TextInputEditText>(R.id.in_referral_code)
        if (!ref.isNullOrBlank() && refField.text.isNullOrBlank()) refField.setText(ref.take(8))
    }

    private fun setupRegister(v: View) {
        val districts = (1..23).map { it.toString() }
        v.findViewById<MaterialAutoCompleteTextView>(R.id.in_district)
            .setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, districts))

        val city = v.findViewById<TextInputEditText>(R.id.in_city)
        val tilDistrict = v.findViewById<TextInputLayout>(R.id.til_district)
        city.doAfterTextChanged {
            tilDistrict.isVisible = it.toString().trim().lowercase().startsWith("budapest")
        }
        v.findViewById<TextView>(R.id.privacyLink).setOnClickListener {
            activity.openInWeb(activity.getString(R.string.privacy_url))
        }
        v.findViewById<MaterialButton>(R.id.submit).setOnClickListener { submitRegister(v) }
    }

    private fun text(v: View, id: String): String {
        val resId = activity.resources.getIdentifier("in_$id", "id", activity.packageName)
        return (v.findViewById<TextView>(resId).text?.toString() ?: "").trim()
    }

    private fun til(v: View, id: String): TextInputLayout {
        val resId = activity.resources.getIdentifier("til_$id", "id", activity.packageName)
        return v.findViewById(resId)
    }

    private fun submitRegister(v: View) {
        val err = v.findViewById<TextView>(R.id.regError)
        err.isVisible = false
        listOf("name", "phone", "email", "postal_code", "city", "address").forEach { til(v, it).error = null }

        var ok = true
        fun req(id: String) { if (text(v, id).isEmpty()) { til(v, id).error = activity.getString(R.string.err_required); ok = false } }
        listOf("name", "phone", "postal_code", "city", "address").forEach { req(it) }

        val postal = text(v, "postal_code")
        if (postal.isNotEmpty() && !Regex("^\\d{4}$").matches(postal)) {
            til(v, "postal_code").error = activity.getString(R.string.err_postal); ok = false
        }
        val phoneDigits = text(v, "phone").filter { it.isDigit() }
        if (text(v, "phone").isNotEmpty() && phoneDigits.length !in 9..12) {
            til(v, "phone").error = activity.getString(R.string.err_phone); ok = false
        }
        val email = text(v, "email")
        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            til(v, "email").error = activity.getString(R.string.err_email); ok = false
        }
        val privacy = v.findViewById<MaterialCheckBox>(R.id.privacy).isChecked
        if (!privacy) { err.text = activity.getString(R.string.err_privacy); err.isVisible = true; ok = false }
        if (!ok) return

        val body = JSONObject()
            .put("name", text(v, "name"))
            .put("phone", text(v, "phone"))
            .put("city", text(v, "city"))
            .put("postal_code", postal)
            .put("address", text(v, "address"))
            .put("privacy_accepted", true)
        if (email.isNotEmpty()) body.put("email", email)
        val district = text(v, "district")
        if (v.findViewById<View>(R.id.til_district).isVisible && district.isNotEmpty()) body.put("district", district)
        text(v, "address2").takeIf { it.isNotEmpty() }?.let { body.put("address2", it) }
        text(v, "doorbell").takeIf { it.isNotEmpty() }?.let { body.put("doorbell", it) }
        text(v, "machines_count").toIntOrNull()?.let { body.put("machines_count", it.coerceIn(0, 99)) }
        text(v, "availability_note").takeIf { it.isNotEmpty() }?.let { body.put("availability_note", it) }
        text(v, "notes").takeIf { it.isNotEmpty() }?.let { body.put("notes", it) }
        val ref = text(v, "referral_code").ifEmpty { prefs.referralCode ?: "" }
        if (ref.isNotEmpty()) body.put("referral_code", ref.take(8))

        val btn = v.findViewById<MaterialButton>(R.id.submit)
        btn.isEnabled = false
        btn.text = activity.getString(R.string.reg_sending)

        Api.call(activity, "POST", "/api/app/register", body, auth = false) { r ->
            btn.isEnabled = true
            btn.text = activity.getString(R.string.reg_submit)
            when {
                r.code == 201 -> {
                    val uuid = r.json?.optString("app_uuid").orEmpty()
                    if (uuid.isNotEmpty()) {
                        prefs.token = uuid
                        prefs.referralCode = null
                        activity.updateAccountLabel()
                        registerView = null
                        Toast.makeText(activity, R.string.reg_ok, Toast.LENGTH_SHORT).show()
                        PushHelper.syncToken(activity)
                        render(forceReload = true)
                    }
                }
                r.code == 202 -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.reg_verify_title)
                    .setMessage(R.string.reg_verify_text)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                r.code == 400 -> {
                    val msgs = r.json?.optJSONArray("messages")
                    err.text = msgs?.let { joinArray(it) }?.ifEmpty { null } ?: activity.getString(R.string.err_generic, r.code)
                    err.isVisible = true
                }
                r.code == 429 -> { err.text = activity.getString(R.string.err_rate); err.isVisible = true }
                r.code < 0 -> { err.text = activity.getString(R.string.err_network); err.isVisible = true }
                else -> { err.text = activity.getString(R.string.err_generic, r.code); err.isVisible = true }
            }
        }
    }

    private fun joinArray(a: JSONArray): String =
        (0 until a.length()).joinToString("\n") { "• " + a.optString(it) }

    // ================================================================ FIÓK

    private fun showAccount(forceReload: Boolean) {
        val v = accountView ?: LayoutInflater.from(activity)
            .inflate(R.layout.view_account, container, false).also { setupAccount(it); accountView = it }
        if (mode != "account") {
            container.removeAllViews(); container.addView(v); mode = "account"
        }
        if (customer == null || forceReload) load() else bind()
        maybeAskPush()
    }

    private fun setupAccount(v: View) {
        v.findViewById<SwipeRefreshLayout>(R.id.accSwipe).apply {
            setColorSchemeResources(R.color.brand)
            setOnRefreshListener { load() }
        }
        v.findViewById<MaterialButton>(R.id.refShare).setOnClickListener {
            val code = customer?.optString("referral_code").orEmpty()
            if (code.isEmpty()) return@setOnClickListener
            val link = DeepLink.referralLink(code)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.ref_share_text, link))
            }
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.ref_share_title)))
        }
        v.findViewById<MaterialButton>(R.id.refCopy).setOnClickListener {
            val code = customer?.optString("referral_code").orEmpty()
            if (code.isEmpty()) return@setOnClickListener
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DKC", DeepLink.referralLink(code)))
            Toast.makeText(activity, R.string.ref_copied, Toast.LENGTH_SHORT).show()
        }
        val sw = v.findViewById<MaterialSwitch>(R.id.pushSwitch)
        sw.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            setPush(checked)
        }
        v.findViewById<MaterialButton>(R.id.logout).setOnClickListener {
            activity.confirm(R.string.logout_confirm) {
                if (prefs.pushConsent) PushHelper.sendConsent(activity, false)
                prefs.logout()
                customer = null
                accountView = null
                mode = null
                activity.onLoggedOut()
            }
        }
    }

    private fun setPush(enable: Boolean) {
        val sw = accountView?.findViewById<MaterialSwitch>(R.id.pushSwitch)
        if (enable) {
            activity.requestNotificationPermission { granted ->
                if (!granted) { sw?.isChecked = false; return@requestNotificationPermission }
                PushHelper.sendConsent(activity, true) { ok -> if (!ok) sw?.isChecked = prefs.pushConsent }
                PushHelper.syncToken(activity)
            }
        } else {
            PushHelper.sendConsent(activity, false) { ok -> if (!ok) sw?.isChecked = prefs.pushConsent }
        }
    }

    private fun maybeAskPush() {
        if (prefs.pushConsentAsked || !PushHelper.isAvailable(activity)) return
        prefs.pushConsentAsked = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.push_ask_title)
            .setMessage(R.string.push_ask_text)
            .setPositiveButton(R.string.yes) { _, _ ->
                accountView?.findViewById<MaterialSwitch>(R.id.pushSwitch)?.isChecked = true
                setPush(true)
            }
            .setNegativeButton(R.string.no) { _, _ -> PushHelper.sendConsent(activity, false) }
            .show()
    }

    private fun load() {
        val v = accountView ?: return
        if (loading) return
        loading = true
        val swipe = v.findViewById<SwipeRefreshLayout>(R.id.accSwipe)
        swipe.isRefreshing = true
        Api.call(activity, "GET", "/api/app/customer") { r ->
            loading = false
            swipe.isRefreshing = false
            val err = v.findViewById<TextView>(R.id.accError)
            when {
                r.ok && r.json != null -> { customer = r.json; err.isVisible = false; bind() }
                r.code == 401 -> {
                    prefs.logout(); customer = null; accountView = null; mode = null
                    Toast.makeText(activity, R.string.login_invalid, Toast.LENGTH_LONG).show()
                    activity.onLoggedOut()
                }
                r.code < 0 -> { err.text = activity.getString(R.string.err_network); err.isVisible = true }
                else -> { err.text = activity.getString(R.string.err_generic, r.code); err.isVisible = true }
            }
        }
    }

    private fun s(o: JSONObject, k: String): String =
        if (o.isNull(k)) "" else o.optString(k, "").trim()

    private fun bind() {
        val v = accountView ?: return
        val c = customer ?: return

        val name = s(c, "name")
        v.findViewById<TextView>(R.id.accHello).text =
            if (name.isNotEmpty()) activity.getString(R.string.acc_hello, name) else activity.getString(R.string.acc_title)
        val status = s(c, "status")
        v.findViewById<TextView>(R.id.accStatus).apply {
            text = activity.getString(R.string.acc_status, statusLabel(status)); isVisible = status.isNotEmpty()
        }

        val date = s(c, "next_maintenance_date")
        val time = s(c, "next_maintenance_time")
        v.findViewById<TextView>(R.id.accNext).text = if (date.isEmpty()) activity.getString(R.string.acc_next_none)
        else formatDate(date) + if (time.isNotEmpty()) ", $time óra között" else ""

        val addr = buildString {
            append(s(c, "postal_code")).append(' ').append(s(c, "city"))
            s(c, "district").takeIf { it.isNotEmpty() }?.let { append(", ").append(it).append(". kerület") }
            append('\n').append(s(c, "address"))
            s(c, "address2").takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
            s(c, "doorbell").takeIf { it.isNotEmpty() }?.let { append("\nCsengő: ").append(it) }
        }
        val data = listOfNotNull(
            name.ifEmpty { null },
            s(c, "phone").ifEmpty { null },
            s(c, "email").ifEmpty { null },
            addr.trim(),
        ).joinToString("\n")
        v.findViewById<TextView>(R.id.accData).text = data

        val acs = c.optJSONArray("air_conditioners")
        v.findViewById<TextView>(R.id.accAcs).text = if (acs == null || acs.length() == 0)
            activity.getString(R.string.acc_acs_none)
        else (0 until acs.length()).joinToString("\n") { i ->
            val a = acs.optJSONObject(i) ?: JSONObject()
            "• " + listOf(s(a, "brand"), s(a, "type")).filter { it.isNotEmpty() }.joinToString(" ")
        }

        val code = s(c, "referral_code")
        v.findViewById<TextView>(R.id.accRef).text =
            if (code.isEmpty()) activity.getString(R.string.acc_ref_none) else activity.getString(R.string.acc_ref_text, code)
        v.findViewById<View>(R.id.refShare).isEnabled = code.isNotEmpty()
        v.findViewById<View>(R.id.refCopy).isEnabled = code.isNotEmpty()

        val pushAvail = PushHelper.isAvailable(activity)
        v.findViewById<TextView>(R.id.accPushInfo).text =
            activity.getString(if (pushAvail) R.string.acc_push_text else R.string.acc_push_unavailable)
        v.findViewById<MaterialSwitch>(R.id.pushSwitch).apply {
            isEnabled = pushAvail
            isChecked = prefs.pushConsent
        }
    }

    private fun formatDate(iso: String): String = try {
        val d = java.time.LocalDate.parse(iso)
        d.format(java.time.format.DateTimeFormatter.ofPattern("yyyy. MMMM d., EEEE", java.util.Locale("hu", "HU")))
    } catch (_: Exception) { iso }

    private fun statusLabel(s: String): String = when (s) {
        "pending_call" -> "visszahívásra vár"
        "scheduled" -> "időpont egyeztetve"
        "active" -> "aktív"
        else -> s.replace('_', ' ')
    }
}
