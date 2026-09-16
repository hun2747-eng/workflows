package hu.netlokator.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.regex.Pattern

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = extractUrl(sharedText)

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
                Toast.makeText(this, "Nem található URL a megosztásban.", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun extractUrl(text: String): String {
        val matcher = Pattern.compile("https?://\\S+").matcher(text)
        return if (matcher.find()) matcher.group() else text.trim()
    }
}
