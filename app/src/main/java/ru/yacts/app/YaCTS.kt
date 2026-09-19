package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private const val ACTION_CAPTURE = "ru.yacts.app.CAPTURE"

class MainActivity : Activity() {

    private var inSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isAccessibilityEnabled()) {
            triggerCaptureAndFinish()
        } else {
            inSetup = true
            showSetupScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        if (inSetup && isAccessibilityEnabled()) {
            triggerCaptureAndFinish()
        }
    }

    private fun triggerCaptureAndFinish() {
        startService(Intent(this, YaService::class.java).setAction(ACTION_CAPTURE))
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${YaService::class.java.name}"
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showSetupScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101216"))
            setPadding(48, 80, 48, 80)
        }
        val txt = TextView(this).apply {
            text = "YaCTS\n\n" +
                    "Один раз включите спец. возможности:\n\n" +
                    "1. Нажмите «Открыть настройки»\n" +
                    "2. Найдите «YaCTS» в списке\n" +
                    "3. Включите и подтвердите\n" +
                    "4. Вернитесь сюда"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val btn = Button(this).apply {
            text = "Открыть настройки"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(txt)
        root.addView(btn)
        setContentView(root)
    }
}

class YaService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            captureScreen()
        }
        return START_NOT_STICKY
    }

    private fun captureScreen() {
        try {
            takeScreenshot(
                System.currentTimeMillis(),
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hw = result.hardwareBuffer
                        val cs = result.colorSpace
                        val hardBmp = Bitmap.wrapHardwareBuffer(hw, cs)
                        hw.close()
                        if (hardBmp == null) {
                            Log.e(TAG, "Bitmap is null")
                            return
                        }
                        val soft = hardBmp.copy(Bitmap.Config.ARGB_8888, false)
                        hardBmp.recycle()

                        val baos = ByteArrayOutputStream()
                        soft.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        soft.recycle()
                        val bytes = baos.toByteArray()
                        Log.i(TAG, "Screenshot: ${bytes.size} bytes")

                        Thread { uploadAndOpen(bytes) }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed: $errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot threw", t)
        }
    }

    private fun uploadAndOpen(bytes: ByteArray) {
        try {
            val cbirQuery = uploadToYandex(bytes) ?: run {
                Log.e(TAG, "Upload failed: no cbir url")
                return
            }
            val fullUrl = "https://yandex.com/images/search?$cbirQuery"
            Log.i(TAG, "Opening: $fullUrl")

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            for (pkg in listOf(
                "ru.yandex.yandexbrowser",
                "ru.yandex.browser",
                "com.yandex.browser"
            )) {
                intent.setPackage(pkg)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            }
            intent.setPackage(null)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "uploadAndOpen error", t)
        }
    }

    private fun uploadToYandex(imageBytes: ByteArray): String? {
        val boundary = "----YaCTS${UUID.randomUUID().toString().replace("-", "")}"
        val requestJson = "{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}"
        val urlStr = "https://yandex.com/images/search" +
                "?rpt=imageview" +
                "&format=json" +
                "&request=${URLEncoder.encode(requestJson, "UTF-8")}"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }

        try {
            conn.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"upfile\"; filename=\"screenshot.jpg\"\r\n".toByteArray()
                )
                out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                out.write(imageBytes)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code: ${response.take(300)}")
                return null
            }
            Log.d(TAG, "Yandex response: ${response.take(300)}")
            return parseCbirUrl(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCbirUrl(jsonStr: String): String? = try {
        val json = JSONObject(jsonStr)
        try {
            json.getJSONObject("blocks")
                .getJSONObject("params")
                .getString("url")
        } catch (_: Throwable) {
            try {
                json.getString("url")
            } catch (_: Throwable) {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "YaCTS"
    }
}
