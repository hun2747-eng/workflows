package hu.netlokator.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.regex.Pattern

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            var rawText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (rawText.isEmpty() && intent.clipData != null && intent.clipData!!.itemCount > 0) {
                rawText = intent.clipData!!.getItemAt(0).text?.toString() ?: ""
            }

            var url = extractUrl(rawText)
            if (url.isEmpty() && intent.data != null) {
                val dataStr = intent.data.toString()
                if (dataStr.startsWith("http://") || dataStr.startsWith("https://")) {
                    url = dataStr
                }
            }

            if (url.isNotEmpty()) {
                val inputData = Data.Builder()
                    .putString("TARGET_URL", url)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SendWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(applicationContext).enqueue(workRequest)
                Toast.makeText(this, "Küldés a NetLokátornak...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nem található érvényes link a megosztásban.", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun extractUrl(text: String): String {
        val matcher = Pattern.compile("https?://\S+").matcher(text)
        val extracted = if (matcher.find()) matcher.group() else text.trim()

        if (extracted.contains("share.google")) {
            try {
                val uri = Uri.parse(extracted)
                val paramUrl = uri.getQueryParameter("url") ?: uri.getQueryParameter("q")
                if (!paramUrl.isNullOrEmpty() && (paramUrl.startsWith("http://") || paramUrl.startsWith("https://"))) {
                    return paramUrl
                }
            } catch (_: Exception) {}
        }

        return extracted
    }
}
