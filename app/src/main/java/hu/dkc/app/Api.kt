package hu.dkc.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ApiResult(val code: Int, val body: String) {
    val json: JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }
    val ok: Boolean get() = code in 200..299
}

/**
 * Minimális HTTP kliens a KlimaDesk mobil API-hoz.
 * Minden hívás (a /api/app/register kivételével) Authorization: Bearer <app_uuid> fejlécet kap.
 */
object Api {
    private val executor = Executors.newFixedThreadPool(2)

    fun call(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        callback: ((ApiResult) -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        executor.execute {
            val result = callSync(appCtx, method, path, body, auth)
            callback?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(result) } }
        }
    }

    fun callSync(context: Context, method: String, path: String, body: JSONObject?, auth: Boolean): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DKCApp/${BuildConfig.VERSION_NAME} (Android)")
                if (auth) {
                    Prefs(context).token?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            ApiResult(code, text)
        } catch (e: Exception) {
            ApiResult(-1, e.message ?: "network_error")
        } finally {
            conn?.disconnect()
        }
    }
}
