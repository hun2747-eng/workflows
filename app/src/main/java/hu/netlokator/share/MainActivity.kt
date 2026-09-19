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
import android.view.View
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
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd. HH:mm:ss", Locale.getDefault())

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
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 16)
        }
        rootContainer.addView(title)

        val desc = TextView(this).apply {
            text = "Add meg a NetLokátor API-kulcsot és az alap URL-t, vagy töltsd be a konfigurációs JSON-fájlt."
            textSize = 14f
            setTextColor(Color.parseColor("#475569"))
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
                    .commit()

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

        // Attach history section so it is visible even on the login screen!
        setupHistorySection(rootContainer)
    }

    private fun showStartView() {
        historyContainer = null
        rootContainer.removeAllViews()

        val title = TextView(this).apply {
            text = "NetLokátor"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 8)
        }
        rootContainer.addView(title)

        val savedUrl = prefs.getString("baseUrl", "") ?: ""
        val statusText = TextView(this).apply {
            text = "✓ Bejelentkezve ($savedUrl)"
            textSize = 13f
            setTextColor(Color.parseColor("#15803D"))
            setPadding(0, 0, 0, 32)
        }
        rootContainer.addView(statusText)

        val label = TextView(this).apply {
            text = "Indexelendő weboldal címe:"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
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
            text = "💡 Tipp: A Chrome böngésző 'Megosztás' (Share) menüjéből a 'Send to Netlokator' opcióval azonnal beküldheted az aktuális oldalt a háttérben."
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 24, 0, 24)
        }
        rootContainer.addView(shareInfo)

        val btnLogout = Button(this).apply {
            text = "Kijelentkezés / Beállítások módosítása"
            setOnClickListener {
                prefs.edit().putBoolean("is_logged_in", false).commit()
                showLoginView()
            }
        }
        rootContainer.addView(btnLogout)

        // Attach history section directly below logout button
        setupHistorySection(rootContainer)
    }

    private fun setupHistorySection(parent: LinearLayout) {
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#CBD5E1"))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 4).apply {
                setMargins(0, 48, 0, 32)
            }
            layoutParams = params
        }
        parent.addView(divider)

        val historyHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val historyTitle = TextView(this).apply {
            text = "📋 Beküldött linkek előzményei"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
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
        parent.addView(historyHeaderLayout)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyContainer = container
        parent.addView(container)

        renderHistoryItems()
    }

    private fun renderHistoryItems() {
        val container = historyContainer ?: return
        container.removeAllViews()

        val submissions = HistoryManager.getSubmissions(this)
        if (submissions.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 28, 32, 28)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F1F5F9"))
                    setStroke(2, Color.parseColor("#CBD5E1"))
                    cornerRadius = 16f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val emptyText = TextView(this).apply {
                text = "Még nincsenek mentett beküldések.

Amikor megosztasz egy linket a Chrome-ból (Megosztás -> Send to Netlokator) vagy a fenti START gombbal, az automatikusan megjelenik itt dátum szerint rendezve."
                textSize = 14f
                setTextColor(Color.parseColor("#475569"))
                setLineSpacing(4f, 1.2f)
            }
            emptyCard.addView(emptyText)

            val btnAddSample = Button(this).apply {
                text = "+ Teszt link hozzáadása az előzményekhez"
                textSize = 13f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 20, 0, 0)
                }
                layoutParams = params
                setOnClickListener {
                    HistoryManager.addSubmission(this@MainActivity, "https://netlokator.hu/minta-oldal", true)
                    renderHistoryItems()
                    Toast.makeText(this@MainActivity, "Minta link hozzáadva az előzményekhez!", Toast.LENGTH_SHORT).show()
                }
            }
            emptyCard.addView(btnAddSample)

            container.addView(emptyCard)
            return
        }

        for (item in submissions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF"))
                    setStroke(2, if (item.success) Color.parseColor("#86EFAC") else Color.parseColor("#FCA5A5"))
                    cornerRadius = 16f
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 20)
                }
                layoutParams = params
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val dateText = TextView(this).apply {
                val formattedDate = dateFormat.format(Date(item.timestamp))
                text = "📅 $formattedDate"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#334155"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(dateText)

            val statusBadge = TextView(this).apply {
                text = if (item.success) "✓ Beküldve" else "✗ Hiba"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (item.success) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"))
                setPadding(16, 6, 16, 6)
                background = GradientDrawable().apply {
                    setColor(if (item.success) Color.parseColor("#DCFCE7") else Color.parseColor("#FEE2E2"))
                    cornerRadius = 12f
                }
            }
            topRow.addView(statusBadge)
            card.addView(topRow)

            val urlText = TextView(this).apply {
                val spannable = SpannableString(item.url).apply {
                    setSpan(UnderlineSpan(), 0, length, 0)
                }
                text = spannable
                textSize = 15f
                setTextColor(Color.parseColor("#1D4ED8"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 16, 0, 8)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Hiba: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("NetLokátor URL", item.url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "Link másolva a vágólapra!", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            card.addView(urlText)

            val hintText = TextView(this).apply {
                text = "👆 Érintsd meg a megnyitáshoz, hosszan nyomva másolás"
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
            }
            card.addView(hintText)

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
                            .commit()

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
