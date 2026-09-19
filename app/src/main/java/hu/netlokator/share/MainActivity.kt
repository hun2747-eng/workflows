package hu.netlokator.share

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val PICK_JSON_CODE = 1001
    private lateinit var rootContainer: LinearLayout
    private var historyContainer: LinearLayout? = null
    private val prefs by lazy { getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scroll.addView(rootContainer)
        setContentView(scroll)

        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        val hasKey = prefs.getString("apiKey", "")?.isNotEmpty() == true
        if (isLoggedIn && hasKey) {
            showStartView()
        } else {
            showLoginView()
        }
    }

    override fun onResume() {
        super.onResume()
        if (historyContainer != null) {
            renderHistoryItems()
        }
    }

    private fun showLoginView() {
        historyContainer = null
        rootContainer.removeAllViews()

        val title = TextView(this).apply {
            text = "NetLokátor Bejelentkezés"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        rootContainer.addView(title)

        val desc = TextView(this).apply {
            text = "Add meg a NetLokátor API-kulcsot és az alap URL-t, vagy töltsd be a konfigurációs JSON-fájlt."
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        rootContainer.addView(desc)

        val editBaseUrl = EditText(this).apply {
            hint = "Alap URL (pl. https://kereso.netlokator.hu)"
            setText(prefs.getString("baseUrl", "https://kereso.netlokator.hu"))
            setPadding(24, 24, 24, 24)
        }
        rootContainer.addView(editBaseUrl)

        val editApiKey = EditText(this).apply {
            hint = "X-API-Key"
            setText(prefs.getString("apiKey", ""))
            setPadding(24, 24, 24, 24)
        }
        rootContainer.addView(editApiKey)

        val btnLogin = Button(this).apply {
            text = "Bejelentkezés"
            setPadding(0, 32, 0, 32)
            setOnClickListener {
                val url = editBaseUrl.text.toString().trim()
                val key = editApiKey.text.toString().trim()

                if (url.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Kérlek add meg az alap URL-t!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Kérlek add meg az API-kulcsot!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString("baseUrl", url)
                    .putString("apiKey", key)
                    .putBoolean("is_logged_in", true)
                    .apply()

                Toast.makeText(this@MainActivity, "Sikeres bejelentkezés!", Toast.LENGTH_SHORT).show()
                showStartView()
            }
        }
        rootContainer.addView(btnLogin)

        val btnImport = Button(this).apply {
            text = "Konfiguráció importálása JSON fájlból"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                }
                startActivityForResult(intent, PICK_JSON_CODE)
            }
        }
        rootContainer.addView(btnImport)
    }

    private fun showStartView() {
        rootContainer.removeAllViews()

        val title = TextView(this).apply {
            text = "NetLokátor"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        rootContainer.addView(title)

        val savedUrl = prefs.getString("baseUrl", "") ?: ""
        val statusText = TextView(this).apply {
            text = "✓ Bejelentkezve ($savedUrl)"
            textSize = 13f
            setTextColor(Color.parseColor("#15803D"))
            setPadding(0, 0, 0, 36)
        }
        rootContainer.addView(statusText)

        val label = TextView(this).apply {
            text = "Indexelendő weboldal címe:"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        rootContainer.addView(label)

        val editTargetUrl = EditText(this).apply {
            hint = "https://example.com/cikk"
            setPadding(24, 28, 24, 28)
        }
        rootContainer.addView(editTargetUrl)

        val btnPaste = Button(this).apply {
            text = "Beillesztés a vágólapról"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                    val item = clipboard.primaryClip?.getItemAt(0)
                    val text = item?.text?.toString() ?: ""
                    if (text.isNotEmpty()) {
                        editTargetUrl.setText(text)
                    }
                }
            }
        }
        rootContainer.addView(btnPaste)

        val btnStart = Button(this).apply {
            text = "START – URL KÜLDÉSE"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 36, 0, 36)
            setOnClickListener {
                val targetUrl = editTargetUrl.text.toString().trim()
                if (targetUrl.isEmpty() || !targetUrl.startsWith("http")) {
                    Toast.makeText(this@MainActivity, "Kérlek adj meg egy érvényes weboldal címet (http/https)!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                HistoryManager.addSubmission(this@MainActivity, targetUrl, true)
                renderHistoryItems()

                val sendWork = OneTimeWorkRequestBuilder<SendWorker>()
                    .setInputData(workDataOf("TARGET_URL" to targetUrl))
                    .build()

                WorkManager.getInstance(this@MainActivity).enqueue(sendWork)
                Toast.makeText(this@MainActivity, "Küldés elindítva!", Toast.LENGTH_SHORT).show()
                editTargetUrl.setText("")
            }
        }
        rootContainer.addView(btnStart)

        val shareInfo = TextView(this).apply {
            text = "💡 Tipp: A Chrome vagy bármely böngésző 'Megosztás' (Share) menüjéből is közvetlenül küldhetsz oldalakat a 'Send to Netlokator' opcióval."
            textSize = 13f
            setPadding(0, 24, 0, 24)
        }
        rootContainer.addView(shareInfo)

        val btnLogout = Button(this).apply {
            text = "Kijelentkezés / Beállítások módosítása"
            setOnClickListener {
                prefs.edit().putBoolean("is_logged_in", false).apply()
                showLoginView()
            }
        }
        rootContainer.addView(btnLogout)

        // --- SUBMITTED LINKS SECTION BELOW THE BUTTON ---
        val divider = TextView(this).apply {
            text = ""
            setHeight(4)
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 4).apply {
                setMargins(0, 48, 0, 32)
            }
            layoutParams = params
        }
        rootContainer.addView(divider)

        val historyHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val historyTitle = TextView(this).apply {
            text = "Beküldött linkek előzményei"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        historyHeaderLayout.addView(historyTitle)

        val btnClearHistory = Button(this).apply {
            text = "Törlés"
            textSize = 12f
            setOnClickListener {
                HistoryManager.clearSubmissions(this@MainActivity)
                renderHistoryItems()
                Toast.makeText(this@MainActivity, "Előzmények törölve.", Toast.LENGTH_SHORT).show()
            }
        }
        historyHeaderLayout.addView(btnClearHistory)
        rootContainer.addView(historyHeaderLayout)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootContainer.addView(historyContainer)

        renderHistoryItems()
    }

    private fun renderHistoryItems() {
        val container = historyContainer ?: return
        container.removeAllViews()

        val submissions = HistoryManager.getSubmissions(this)
        if (submissions.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Még nincsenek beküldött linkek. Küldj egy linket a fenti mezőből vagy a böngésző megosztás menüjéből!"
                textSize = 14f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 16, 0, 32)
            }
            container.addView(emptyView)
            return
        }

        for (item in submissions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 24, 28, 24)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(2, Color.parseColor("#E2E8F0"))
                    cornerRadius = 16f
                }
                background = bg
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 20)
                }
                layoutParams = params
            }

            val dateText = TextView(this).apply {
                val formattedDate = dateFormat.format(Date(item.timestamp))
                text = "🕒 $formattedDate"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 0, 0, 8)
            }
            card.addView(dateText)

            val urlText = TextView(this).apply {
                val spannable = SpannableString(item.url).apply {
                    setSpan(UnderlineSpan(), 0, length, 0)
                }
                text = spannable
                textSize = 15f
                setTextColor(Color.parseColor("#2563EB"))
                setTypeface(null, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Nem sikerült megnyitni a böngészőt: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("NetLokátor URL", item.url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "Link vágólapra másolva!", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            card.addView(urlText)

            container.addView(card)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_JSON_CODE && resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openInputStream(data.data!!)?.use { stream ->
                    val text = BufferedReader(InputStreamReader(stream)).readText()
                    val json = JSONObject(text)
                    val baseUrl = json.optString("baseUrl", "")
                    val apiKey = json.optString("apiKey", "")

                    if (baseUrl.isNotEmpty() || apiKey.isNotEmpty()) {
                        prefs.edit()
                            .putString("baseUrl", baseUrl.ifEmpty { "https://kereso.netlokator.hu" })
                            .putString("apiKey", apiKey)
                            .putBoolean("is_logged_in", true)
                            .apply()

                        Toast.makeText(this, "Sikeres importálás és bejelentkezés!", Toast.LENGTH_SHORT).show()
                        showStartView()
                    } else {
                        Toast.makeText(this, "A JSON nem tartalmazott 'baseUrl' vagy 'apiKey' kulcsot.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Hiba a fájl olvasásakor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
