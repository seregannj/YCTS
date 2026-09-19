package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class MainActivity : Activity() {

    private var waitingForAccessibility = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAccessibilityEnabled()) {
            requestCapture()
        } else {
            waitingForAccessibility = true
            showSetupScreen()
        }
    }

    override fun onResume() {
        super.onResume()

        if (waitingForAccessibility) {
            handler.postDelayed({
                if (isAccessibilityEnabled()) {
                    waitingForAccessibility = false
                    requestCapture()
                }
            }, 500)
        }
    }

    private fun requestCapture() {
        Log.i(TAG, "Requesting screenshot")

        YaService.requestCapture()

        handler.postDelayed({
            YaService.requestCapture()
        }, 1000)

        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${YaService::class.java.name}"

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }

    private fun showSetupScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101216"))
            setPadding(48, 80, 48, 80)
        }

        val title = TextView(this).apply {
            text = "YaCTS"
            setTextColor(Color.WHITE)
            textSize = 28f
        }

        val description = TextView(this).apply {
            text = """
                
                Для работы YaCTS нужно один раз включить специальную возможность.

                1. Нажмите «Открыть настройки»
                2. Найдите YaCTS
                3. Включите службу
                4. Вернитесь в YaCTS

                После этого скриншот будет делаться автоматически.
            """.trimIndent()

            setTextColor(Color.WHITE)
            textSize = 18f
        }

        val button = Button(this).apply {
            text = "Открыть настройки"

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        root.addView(title)
        root.addView(description)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        params.topMargin = 50

        root.addView(button, params)

        setContentView(root)
    }

    companion object {
        private const val TAG = "YaCTS"
    }
}


class YaService : AccessibilityService() {

    companion object {

        private const val TAG = "YaCTS"

        @Volatile
        private var instance: YaService? = null

        @Volatile
        private var captureRequested = false

        @Volatile
        private var captureInProgress = false

        fun requestCapture() {
            Log.i(TAG, "requestCapture()")

            captureRequested = true

            val service = instance

            if (service != null) {
                service.mainExecutor.execute {
                    service.processCaptureRequest()
                }
            } else {
                Log.i(TAG, "Service is not connected yet")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.i(TAG, "AccessibilityService connected")

        if (captureRequested) {
            mainExecutor.execute {
                processCaptureRequest()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events are not required.
    }

    override fun onInterrupt() {
        Log.i(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "AccessibilityService destroyed")

        if (instance === this) {
            instance = null
        }

        captureRequested = false
        captureInProgress = false

        super.onDestroy()
    }

    private fun processCaptureRequest() {
        if (!captureRequested) {
            return
        }

        if (captureInProgress) {
            Log.i(TAG, "Capture already in progress")
            return
        }

        captureRequested = false
        captureInProgress = true

        Log.i(TAG, "Starting screenshot")

        captureScreen()
    }

    private fun captureScreen() {
        try {

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(result: ScreenshotResult) {

                        Log.i(TAG, "Screenshot captured")

                        try {

                            val hardwareBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace

                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                colorSpace
                            )

                            hardwareBuffer.close()

                            if (hardwareBitmap == null) {
                                Log.e(TAG, "Bitmap is null")
                                captureInProgress = false
                                return
                            }

                            val bitmap = hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                            hardwareBitmap.recycle()

                            if (bitmap == null) {
                                Log.e(
                                    TAG,
                                    "Failed to create software bitmap"
                                )
                                captureInProgress = false
                                return
                            }

                            val output = ByteArrayOutputStream()

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                90,
                                output
                            )

                            bitmap.recycle()

                            val imageBytes = output.toByteArray()

                            Log.i(
                                TAG,
                                "Screenshot JPEG size: ${imageBytes.size} bytes"
                            )

                            Thread {
                                try {
                                    uploadAndOpen(imageBytes)
                                } finally {
                                    captureInProgress = false
                                }
                            }.start()

                        } catch (e: Throwable) {

                            Log.e(
                                TAG,
                                "Error processing screenshot",
                                e
                            )

                            captureInProgress = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {

                        Log.e(
                            TAG,
                            "Screenshot failed. Error code: $errorCode"
                        )

                        captureInProgress = false
                    }
                }
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "takeScreenshot() exception",
                e
            )

            captureInProgress = false
        }
    }

    private fun uploadAndOpen(imageBytes: ByteArray) {

        try {

            Log.i(TAG, "Uploading screenshot to Yandex")

            val cbirQuery = uploadToYandex(imageBytes)

            if (cbirQuery == null) {
                Log.e(TAG, "Yandex upload failed")
                return
            }

            val fullUrl =
                "https://yandex.com/images/search?$cbirQuery"

            Log.i(TAG, "Yandex URL: $fullUrl")

            openYandex(fullUrl)

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "uploadAndOpen() error",
                e
            )
        }
    }

    private fun openYandex(url: String) {

        val uri = Uri.parse(url)

        val yandexPackages = listOf(
            "ru.yandex.searchplugin",
            "ru.yandex.searchapp",
            "ru.yandex.yandexbrowser",
            "ru.yandex.browser",
            "com.yandex.browser"
        )

        for (packageName in yandexPackages) {

            try {

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                startActivity(intent)

                Log.i(
                    TAG,
                    "Opened Yandex package: $packageName"
                )

                return

            } catch (_: ActivityNotFoundException) {
                // Try next package.
            } catch (e: Throwable) {

                Log.e(
                    TAG,
                    "Error opening package $packageName",
                    e
                )
            }
        }

        try {

            val fallbackIntent = Intent(
                Intent.ACTION_VIEW,
                uri
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(fallbackIntent)

            Log.i(
                TAG,
                "Opened Yandex URL using default browser"
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "Could not open Yandex URL",
                e
            )
        }
    }

    private fun uploadToYandex(
        imageBytes: ByteArray
    ): String? {

        val boundary =
            "----YaCTS${UUID.randomUUID().toString().replace("-", "")}"

        val requestJson =
            "{\"blocks\":[{\"block\":\"b-page_type_search-by-image__link\"}]}"

        val encodedRequest =
            URLEncoder.encode(
                requestJson,
                "UTF-8"
            )

        val urlString =
            "https://yandex.com/images/search" +
                    "?rpt=imageview" +
                    "&format=json" +
                    "&request=$encodedRequest"

        val connection =
            (URL(urlString).openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"

                doOutput = true
                doInput = true

                connectTimeout = 30_000
                readTimeout = 30_000

                setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 " +
                            "(Linux; Android 13) " +
                            "AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 " +
                            "Mobile Safari/537.36"
                )

                setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
                )
            }

        try {

            connection.outputStream.use { output ->

                output.write(
                    "--$boundary\r\n".toByteArray()
                )

                output.write(
                    (
                        "Content-Disposition: form-data; " +
                                "name=\"upfile\"; " +
                                "filename=\"screenshot.jpg\"\r\n"
                    ).toByteArray()
                )

                output.write(
                    "Content-Type: image/jpeg\r\n\r\n".toByteArray()
                )

                output.write(imageBytes)

                output.write(
                    "\r\n--$boundary--\r\n".toByteArray()
                )
            }

            val responseCode =
                connection.responseCode

            Log.i(
                TAG,
                "Yandex HTTP response: $responseCode"
            )

            val responseStream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                responseStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

            Log.d(
                TAG,
                "Yandex response: ${response.take(1000)}"
            )

            if (responseCode !in 200..299) {

                Log.e(
                    TAG,
                    "Yandex HTTP error $responseCode"
                )

                return null
            }

            return parseCbirUrl(response)

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "Yandex upload exception",
                e
            )

            return null

        } finally {

            connection.disconnect()
        }
    }

    private fun parseCbirUrl(
        jsonString: String
    ): String? {

        return try {

            val json =
                JSONObject(jsonString)

            try {

                val blocks =
                    json.getJSONObject("blocks")

                val params =
                    blocks.getJSONObject("params")

                return params.getString("url")

            } catch (_: Throwable) {
                // Try another format.
            }

            try {

                return json.getString("url")

            } catch (_: Throwable) {
                // URL not found.
            }

            null

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "Could not parse Yandex response",
                e
            )

            null
        }
    }
}
