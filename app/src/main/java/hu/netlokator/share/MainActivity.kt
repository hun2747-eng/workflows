package hu.netlokator.share

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {

    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private val PICK_JSON_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "NetLokátor Beállítások"
            textSize = 22f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        val prefs = getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE)

        editBaseUrl = EditText(this).apply {
            hint = "Alap URL (pl. https://kereso.netlokator.hu)"
            setText(prefs.getString("baseUrl", ""))
        }
        layout.addView(editBaseUrl)

        editApiKey = EditText(this).apply {
            hint = "X-API-Key"
            setText(prefs.getString("apiKey", ""))
        }
        layout.addView(editApiKey)

        val btnSave = Button(this).apply {
            text = "Mentés"
            setOnClickListener {
                prefs.edit()
                    .putString("baseUrl", editBaseUrl.text.toString().trim())
                    .putString("apiKey", editApiKey.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Beállítások elmentve!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnSave)

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
        layout.addView(btnImport)

        setContentView(layout)
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
                        if (baseUrl.isNotEmpty()) editBaseUrl.setText(baseUrl)
                        if (apiKey.isNotEmpty()) editApiKey.setText(apiKey)

                        getSharedPreferences("netlokator_prefs", Context.MODE_PRIVATE).edit()
                            .putString("baseUrl", editBaseUrl.text.toString().trim())
                            .putString("apiKey", editApiKey.text.toString().trim())
                            .apply()

                        Toast.makeText(this, "Sikeres importálás és mentés!", Toast.LENGTH_SHORT).show()
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
