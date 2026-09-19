package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class MainActivity : Activity() {

    private var setupMode = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "YaCTS opened")

        if (isAccessibilityEnabled()) {
            Log.i(TAG, "Accessibility is ENABLED")
            requestCapture()
        } else {
            Log.i(TAG, "Accessibility is DISABLED")
            setupMode = true
            showSetupScreen()
        }
    }

    override fun onResume() {
        super.onResume()

        if (setupMode) {
            /*
             * После выхода из системных настроек AccessibilityService
             * может подключиться не мгновенно.
             *
             * Поэтому проверяем несколько раз с небольшой задержкой.
             */
            handler.postDelayed({
                if (isAccessibilityEnabled()) {
                    Log.i(TAG, "Accessibility became ENABLED")
                    requestCapture()
                }
            }, 500)
        }
    }

    private fun requestCapture() {
        Log.i(TAG, "Requesting screenshot")

        YaService.requestCapture()

        /*
         * Дополнительная попытка через секунду.
         *
         * Она нужна на случай, если Android ещё не успел
         * подключить AccessibilityService.
         */
        handler.postDelayed({
            YaService.requestCapture()
        }, 1000)

        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected =
            "$packageName/${YaService::class.java.name}"

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(':')
            .any {
                it.equals(expected, ignoreCase = true)
            }
    }

    private fun showSetupScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101216"))
            setPadding(48, 80, 48, 80)
        }

        val txt = TextView(this).apply {
            text =
                "YaCTS\n\n" +
                "Один раз включите спец. возможности:\n\n" +
                "1. Нажмите «Открыть настройки»\n" +
                "2. Найдите «YaCTS»\n" +
                "3. Включите службу\n" +
                "4. Вернитесь назад"
            setTextColor(Color.WHITE)
            textSize = 18f
        }

        val btn = Button(this).apply {
            text = "Открыть настройки"

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        root.addView(txt)
        root.addView(btn)

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

        /**
         * MainActivity вызывает этот метод.
         *
         * AccessibilityService при этом НЕ запускается через
         * startService().
         *
         * Если сервис уже подключён — screenshot делается сразу.
         * Если Android ещё подключает сервис — запрос запоминается
         * и будет выполнен в onServiceConnected().
         */
        fun requestCapture() {
            Log.i(TAG, "requestCapture()")

            captureRequested = true

            val service = instance

            if (service != null) {
                service.mainExecutor.execute {
                    service.processCaptureRequest()
                }
            } else {
                Log.i(
                    TAG,
                    "Service is not connected yet. Waiting for onServiceConnected()"
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.i(TAG, "AccessibilityService CONNECTED")

        if (captureRequested) {
            mainExecutor.execute {
                processCaptureRequest()
            }
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Нам не нужны Accessibility events.
    }

    override fun onInterrupt() {
        Log.i(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "AccessibilityService destroyed")

        instance = null
        captureRequested = false
        captureInProgress = false

        super.onDestroy()
    }

    /**
     * Получает screenshot текущего экрана.
     */
    private fun captureScreen() {

        try {

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(result: ScreenshotResult) {

                        Log.i(TAG, "Screenshot SUCCESS")

                        try {

                            val hardwareBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace

                            val hardwareBitmap =
                                Bitmap.wrapHardwareBuffer(
                                    hardwareBuffer,
                                    colorSpace
                                )

                            hardwareBuffer.close()

                            if (hardwareBitmap == null) {
                                Log.e(
                                    TAG,
                                    "Bitmap.wrapHardwareBuffer returned null"
                                )

                                captureInProgress = false
                                return
                            }

                            val bitmap =
                                hardwareBitmap.copy(
                                    Bitmap.Config.ARGB_8888,
                                    false
                                )

                            hardwareBitmap.recycle()

                            val output =
                                ByteArrayOutputStream()

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                90,
                                output
                            )

                            bitmap.recycle()

                            val imageBytes =
                                output.toByteArray()

                            Log.i(
                                TAG,
                                "Screenshot converted: ${imageBytes.size} bytes"
                            )

                            /*
                             * Сеть выполняем не в main thread.
                             */
                            Thread {

                                uploadAndOpen(imageBytes)

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
                            "Screenshot FAILED. Error code: $errorCode"
                        )

                        captureInProgress = false
                    }
                }
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "takeScreenshot() threw exception",
                e
            )

            captureInProgress = false
        }
    }

    /**
     * Отправляет screenshot в Yandex.
     */
    private fun uploadAndOpen(imageBytes: ByteArray) {

        try {

            Log.i(TAG, "Uploading screenshot to Yandex...")

            val cbirQuery =
                uploadToYandex(imageBytes)

            if (cbirQuery == null) {

                Log.e(
                    TAG,
                    "Yandex upload failed"
                )

                captureInProgress = false
                return
            }

            val fullUrl =
                if (cbirQuery.startsWith("http")) {
                    cbirQuery
                } else {
                    "https://yandex.com/images/search?$cbirQuery"
                }

            Log.i(
                TAG,
                "Yandex URL: $fullUrl"
            )

            openYandex(fullUrl)

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "uploadAndOpen() error",
                e
            )

        } finally {

            captureInProgress = false
        }
    }

    /**
     * Открывает результат в приложении Yandex.
     */
    private fun openYandex(url: String) {

        val uri = Uri.parse(url)

        /*
         * Основная цель — приложение Yandex.
         *
         * У разных версий Yandex package может отличаться,
         * поэтому пробуем несколько вариантов.
         */
        val packages = listOf(
            "ru.yandex.searchapp",
            "ru.yandex.yandexmaps",
            "ru.yandex.yandexbrowser",
            "ru.yandex.browser",
            "com.yandex.browser"
        )

        for (packageName in packages) {

            try {

                val intent =
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    ).apply {

                        setPackage(packageName)

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                if (intent.resolveActivity(packageManager) != null) {

                    Log.i(
                        TAG,
                        "Opening Yandex package: $packageName"
                    )

                    startActivity(intent)
                    return
                }

            } catch (e: Throwable) {

                Log.e(
                    TAG,
                    "Failed to open package $packageName",
                    e
                )
            }
        }

        /*
         * Если конкретное приложение не найдено,
         * используем обычный Android resolver.
         */
        try {

            Log.i(
                TAG,
                "Yandex app not found. Opening generic browser."
            )

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            startActivity(intent)

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "Cannot open URL",
                e
            )
        }
    }

    /**
     * HTTP upload изображения в Yandex Images.
     */
    private fun uploadToYandex(
        imageBytes: ByteArray
    ): String? {

        val boundary =
            "----YaCTS${UUID.randomUUID()}"
                .replace("-", "")

        val requestJson =
            """
            {
              "blocks": [
                {
                  "block": "b-page_type_search-by-image__link"
                }
              ]
            }
            """.trimIndent()

        val urlString =
            "https://yandex.com/images/search" +
                    "?rpt=imageview" +
                    "&format=json" +
                    "&request=" +
                    URLEncoder.encode(
                        requestJson,
                        "UTF-8"
                    )

        val connection =
            (URL(urlString).openConnection()
                    as HttpURLConnection).apply {

                requestMethod = "POST"

                doOutput = true

                connectTimeout = 30000
                readTimeout = 30000

                setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) " +
                            "AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
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
                        "Content-Disposition: " +
                                "form-data; " +
                                "name=\"upfile\"; " +
                                "filename=\"screenshot.jpg\"\r\n"
                        ).toByteArray()
                )

                output.write(
                    "Content-Type: image/jpeg\r\n\r\n"
                        .toByteArray()
                )

                output.write(imageBytes)

                output.write(
                    "\r\n--$boundary--\r\n"
                        .toByteArray()
                )
            }

            val responseCode =
                connection.responseCode

            Log.i(
                TAG,
                "Yandex HTTP response: $responseCode"
            )

            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                stream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

            Log.d(
                TAG,
                "Yandex response: ${response.take(2000)}"
            )

            if (responseCode !in 200..299) {

                Log.e(
                    TAG,
                    "Yandex returned HTTP $responseCode"
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

    /**
     * Пытается достать URL результата из ответа Yandex.
     *
     * Здесь оставлено несколько вариантов,
     * потому что формат ответа Yandex может различаться.
     */
    private fun parseCbirUrl(
        response: String
    ): String? {

        /*
         * Вариант 1 — обычный JSON.
         */
        try {

            val json =
                JSONObject(response)

            val directUrl =
                json.optString("url", "")

            if (directUrl.isNotEmpty()) {
                return directUrl
            }

            val cbirUrl =
                json.optString("cbirUrl", "")

            if (cbirUrl.isNotEmpty()) {
                return cbirUrl
            }

            val cbirId =
                json.optString("cbir_id", "")

            if (cbirId.isNotEmpty()) {

                return "rpt=imageview&cbir_id=" +
                        URLEncoder.encode(
                            cbirId,
                            "UTF-8"
                        )
            }

        } catch (_: Throwable) {
            // Продолжаем поиск.
        }

        /*
         * Вариант 2 — blocks может быть массивом.
         */
        try {

            val json =
                JSONObject(response)

            val blocks =
                json.opt("blocks")

            if (blocks is JSONArray) {

                for (i in 0 until blocks.length()) {

                    val block =
                        blocks.optJSONObject(i)
                            ?: continue

                    val params =
                        block.optJSONObject("params")

                    val url =
                        params?.optString(
                            "url",
                            ""
                        ) ?: ""

                    if (url.isNotEmpty()) {
                        return url
                    }

                    val direct =
                        block.optString(
                            "url",
                            ""
                        )

                    if (direct.isNotEmpty()) {
                        return direct
                    }
                }
            }

        } catch (_: Throwable) {
            // Ниже fallback.
        }

        /*
         * Вариант 3 — иногда URL можно найти прямо
         * в текстовом ответе.
         */
        val match =
            Regex(
                """https?://[^"\s]+"""
            ).find(response)

        if (match != null) {
            return match.value
        }

        Log.e(
            TAG,
            "Could not find Yandex result URL"
        )

        return null
    }
}
