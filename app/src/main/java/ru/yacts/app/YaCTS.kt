package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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

    private var inSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAccessibilityEnabled()) {
            requestCaptureAndFinish()
        } else {
            inSetup = true
            showSetupScreen()
        }
    }

    override fun onResume() {
        super.onResume()

        if (inSetup && isAccessibilityEnabled()) {
            requestCaptureAndFinish()
        }
    }

    /**
     * AccessibilityService нельзя запускать через startService().
     *
     * Сервис запускается самой Android-системой после того,
     * как пользователь включил его в настройках.
     *
     * Поэтому мы только передаём ему запрос на скриншот.
     */
    private fun requestCaptureAndFinish() {
        YaService.requestCapture()
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(
            this,
            YaService::class.java
        ).flattenToString()

        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return raw.split(':').any {
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
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        root.addView(txt)
        root.addView(btn)

        setContentView(root)
    }
}


class YaService : AccessibilityService() {

    companion object {

        private const val TAG = "YaCTS"

        @Volatile
        private var instance: YaService? = null

        @Volatile
        private var captureRequested = false

        /**
         * Вызывается MainActivity.
         *
         * Если сервис уже подключён — сразу делаем скриншот.
         *
         * Если Android ещё не успел вызвать onServiceConnected(),
         * запоминаем запрос и выполним его сразу после подключения.
         */
        fun requestCapture() {
            captureRequested = true

            instance?.let {
                captureRequested = false
                it.captureScreen()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.i(TAG, "Accessibility service connected")

        /*
         * MainActivity могла отправить запрос буквально
         * в момент, когда Android ещё подключал сервис.
         */
        if (captureRequested) {
            captureRequested = false

            /*
             * Небольшая задержка даёт системе закончить
             * переключение из настроек Accessibility.
             */
            mainExecutor.execute {
                captureScreen()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Нам не нужны accessibility events.
    }

    override fun onInterrupt() {
        // Ничего делать не нужно.
    }

    override fun onDestroy() {
        instance = null
        captureRequested = false

        Log.i(TAG, "Accessibility service destroyed")

        super.onDestroy()
    }

    /**
     * Получаем скриншот всего основного дисплея.
     *
     * API 30+:
     * takeScreenshot(displayId, executor, callback)
     */
    private fun captureScreen() {
        try {
            Log.i(TAG, "Starting screenshot...")

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(
                        result: ScreenshotResult
                    ) {
                        var hardwareBufferClosed = false

                        try {
                            val hardwareBuffer =
                                result.hardwareBuffer

                            if (hardwareBuffer == null) {
                                Log.e(
                                    TAG,
                                    "Screenshot returned null HardwareBuffer"
                                )
                                return
                            }

                            try {
                                val hardwareBitmap =
                                    Bitmap.wrapHardwareBuffer(
                                        hardwareBuffer,
                                        result.colorSpace
                                    )

                                if (hardwareBitmap == null) {
                                    Log.e(
                                        TAG,
                                        "Bitmap.wrapHardwareBuffer() returned null"
                                    )
                                    return
                                }

                                /*
                                 * Переводим hardware bitmap
                                 * в обычный ARGB_8888 bitmap.
                                 */
                                val bitmap =
                                    hardwareBitmap.copy(
                                        Bitmap.Config.ARGB_8888,
                                        false
                                    )

                                hardwareBitmap.recycle()

                                if (bitmap == null) {
                                    Log.e(
                                        TAG,
                                        "Bitmap copy returned null"
                                    )
                                    return
                                }

                                val output =
                                    ByteArrayOutputStream()

                                bitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    85,
                                    output
                                )

                                bitmap.recycle()

                                val bytes =
                                    output.toByteArray()

                                output.close()

                                Log.i(
                                    TAG,
                                    "Screenshot ready: ${bytes.size} bytes"
                                )

                                /*
                                 * Сеть выполняем НЕ в main thread.
                                 */
                                Thread {
                                    uploadAndOpen(bytes)
                                }.start()

                            } finally {
                                hardwareBuffer.close()
                                hardwareBufferClosed = true
                            }

                        } catch (t: Throwable) {
                            Log.e(
                                TAG,
                                "Error processing screenshot",
                                t
                            )

                            if (!hardwareBufferClosed) {
                                try {
                                    result.hardwareBuffer?.close()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(
                            TAG,
                            "Screenshot failed. Error code: $errorCode"
                        )
                    }
                }
            )

        } catch (t: Throwable) {
            Log.e(
                TAG,
                "takeScreenshot() threw exception",
                t
            )
        }
    }

    /**
     * Загружает изображение в Yandex Images
     * и открывает полученный поиск.
     */
    private fun uploadAndOpen(bytes: ByteArray) {
        try {
            Log.i(TAG, "Uploading screenshot to Yandex...")

            val cbirQuery = uploadToYandex(bytes)

            if (cbirQuery.isNullOrBlank()) {
                Log.e(
                    TAG,
                    "Yandex upload failed: CBIR URL is empty"
                )
                return
            }

            /*
             * Ответ Yandex может вернуть как полный URL,
             * так и query-параметры.
             */
            val fullUrl =
                if (
                    cbirQuery.startsWith("http://") ||
                    cbirQuery.startsWith("https://")
                ) {
                    cbirQuery
                } else {
                    "https://yandex.com/images/search?$cbirQuery"
                }

            Log.i(TAG, "Opening Yandex: $fullUrl")

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(fullUrl)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            /*
             * Сначала пробуем Yandex Browser.
             */
            val yandexPackages = listOf(
                "ru.yandex.yandexbrowser",
                "ru.yandex.browser",
                "com.yandex.browser"
            )

            for (pkg in yandexPackages) {
                val browserIntent =
                    Intent(intent).apply {
                        setPackage(pkg)
                    }

                if (
                    browserIntent.resolveActivity(
                        packageManager
                    ) != null
                ) {
                    startActivity(browserIntent)
                    return
                }
            }

            /*
             * Если Yandex Browser нет —
             * открываем обычным браузером.
             */
            intent.setPackage(null)

            if (
                intent.resolveActivity(packageManager) != null
            ) {
                startActivity(intent)
            } else {
                Log.e(
                    TAG,
                    "No browser available"
                )
            }

        } catch (t: Throwable) {
            Log.e(
                TAG,
                "uploadAndOpen() failed",
                t
            )
        }
    }

    /**
     * Загружает JPEG в Yandex Images.
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

        val encodedRequest =
            URLEncoder.encode(
                requestJson,
                "UTF-8"
            )

        val urlStr =
            "https://yandex.com/images/search" +
                    "?rpt=imageview" +
                    "&format=json" +
                    "&request=$encodedRequest"

        var connection: HttpURLConnection? = null

        try {
            connection =
                (URL(urlStr).openConnection()
                        as HttpURLConnection).apply {

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
                        "Accept",
                        "application/json, text/plain, */*"
                    )

                    setRequestProperty(
                        "Accept-Language",
                        "ru-RU,ru;q=0.9,en;q=0.8"
                    )

                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) " +
                                "AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 " +
                                "Mobile Safari/537.36"
                    )
                }

            /*
             * multipart/form-data
             */
            connection.outputStream.use { out ->

                out.write(
                    "--$boundary\r\n".toByteArray()
                )

                out.write(
                    (
                        "Content-Disposition: form-data; " +
                                "name=\"upfile\"; " +
                                "filename=\"screenshot.jpg\"\r\n"
                        ).toByteArray()
                )

                out.write(
                    "Content-Type: image/jpeg\r\n\r\n"
                        .toByteArray()
                )

                out.write(imageBytes)

                out.write(
                    "\r\n--$boundary--\r\n"
                        .toByteArray()
                )

                out.flush()
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
                stream?.bufferedReader()?.use {
                    it.readText()
                } ?: ""

            /*
             * На время отладки выводим полный ответ.
             *
             * Это очень важно, потому что формат
             * ответа Yandex может измениться.
             */
            Log.d(
                TAG,
                "Yandex response: $response"
            )

            if (responseCode !in 200..299) {
                Log.e(
                    TAG,
                    "Yandex HTTP error: $responseCode"
                )
                return null
            }

            return parseCbirUrl(response)

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Yandex upload exception",
                t
            )

            return null

        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Извлекает URL/CBIR query из ответа Yandex.
     *
     * Поддерживаем несколько возможных вариантов структуры,
     * чтобы приложение не ломалось при небольшом изменении JSON.
     */
    private fun parseCbirUrl(
        jsonStr: String
    ): String? {

        try {
            val json =
                JSONObject(jsonStr)

            /*
             * Вариант:
             *
             * {
             *   "blocks": [
             *      {
             *        "params": {
             *           "url": "..."
             *        }
             *      }
             *   ]
             * }
             */
            val blocks =
                json.optJSONArray("blocks")

            if (blocks != null) {

                for (i in 0 until blocks.length()) {

                    val block =
                        blocks.optJSONObject(i)
                            ?: continue

                    val params =
                        block.optJSONObject("params")

                    val url =
                        params?.optString("url")

                    if (!url.isNullOrBlank()) {
                        return url
                    }

                    /*
                     * Иногда нужные данные могут находиться
                     * непосредственно в block.
                     */
                    val directUrl =
                        block.optString("url")

                    if (directUrl.isNotBlank()) {
                        return directUrl
                    }
                }
            }

            /*
             * Вариант с обычным "url".
             */
            val directUrl =
                json.optString("url")

            if (directUrl.isNotBlank()) {
                return directUrl
            }

            /*
             * Иногда URL может быть в cbirUrl.
             */
            val cbirUrl =
                json.optString("cbirUrl")

            if (cbirUrl.isNotBlank()) {
                return cbirUrl
            }

            /*
             * Иногда ответ может содержать
             * строковое поле cbir_id.
             */
            val cbirId =
                json.optString("cbir_id")

            if (cbirId.isNotBlank()) {
                return "cbir_id=${
                    URLEncoder.encode(
                        cbirId,
                        "UTF-8"
                    )
                }&rpt=imageview"
            }

            Log.e(
                TAG,
                "Could not find CBIR URL in Yandex response"
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "Failed to parse Yandex JSON",
                e
            )
        }

        return null
    }
}
