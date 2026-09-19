package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "YaCTS"
private const val YANDEX_PACKAGE = "com.yandex.searchapp"

/**
 * Точка входа приложения.
 *
 * ВАЖНО:
 * Здесь НЕТ повторного запроса через Handler.
 *
 * Один запуск YaCTS = ровно один запрос на скриншот.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAccessibilityEnabled()) {
            Log.d(TAG, "Accessibility включён — запрашиваем один скриншот")
            YaService.requestCapture()
        } else {
            Log.d(TAG, "Accessibility выключен — открываем настройки")

            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Не удалось открыть настройки Accessibility",
                    e
                )
            }
        }

        /*
         * Activity нам больше не нужна.
         *
         * Сам скриншот выполняется AccessibilityService
         * асинхронно, поэтому finish() здесь безопасен.
         */
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val accessibilityManager =
            getSystemService(ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager

        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )

        return enabledServices.any { service ->
            service.resolveInfo?.serviceInfo?.packageName == packageName
        }
    }
}


/**
 * AccessibilityService, который:
 *
 * 1. получает ОДИН запрос;
 * 2. делает ОДИН screenshot текущего экрана;
 * 3. преобразует его в Bitmap;
 * 4. преобразует Bitmap в JPEG;
 * 5. сохраняет JPEG через FileProvider;
 * 6. передаёт URI приложению Яндекс.
 *
 * Яндекс запускается ТОЛЬКО после успешного получения
 * и обработки исходного скриншота.
 */
class YaService : AccessibilityService() {

    companion object {

        @Volatile
        private var instance: YaService? = null

        /**
         * Есть ли ожидающий запрос на screenshot.
         */
        @Volatile
        private var captureRequested = false

        /**
         * Выполняется ли сейчас screenshot.
         *
         * Это дополнительная защита от повторного запуска.
         */
        @Volatile
        private var captureInProgress = false

        /**
         * Запросить ОДИН screenshot.
         *
         * Повторный вызов во время уже выполняющегося
         * screenshot ничего не делает.
         */
        fun requestCapture() {

            synchronized(this) {

                /*
                 * Если уже идёт захват — новый захват
                 * создавать нельзя.
                 */
                if (captureInProgress) {
                    Log.d(
                        TAG,
                        "requestCapture(): screenshot уже выполняется"
                    )
                    return
                }

                /*
                 * Если запрос уже ожидает обработки,
                 * второй запрос тоже не нужен.
                 */
                if (captureRequested) {
                    Log.d(
                        TAG,
                        "requestCapture(): запрос уже ожидает обработки"
                    )
                    return
                }

                captureRequested = true

                Log.d(
                    TAG,
                    "requestCapture(): создан новый запрос"
                )
            }

            /*
             * Если Service уже подключён — начинаем
             * screenshot сразу.
             *
             * Если ещё не подключён, onServiceConnected()
             * увидит captureRequested и запустит его там.
             */
            instance?.startRequestedCapture()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.d(
            TAG,
            "AccessibilityService подключён"
        )

        /*
         * Если запрос пришёл до подключения Service,
         * запускаем его теперь.
         */
        startRequestedCapture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        /*
         * Accessibility-события нам не нужны.
         *
         * Нам нужен только API takeScreenshot().
         */
    }

    override fun onInterrupt() {
        Log.d(
            TAG,
            "AccessibilityService прерван"
        )
    }

    override fun onDestroy() {
        Log.d(
            TAG,
            "AccessibilityService уничтожается"
        )

        synchronized(YaService::class.java) {
            if (instance === this) {
                instance = null
            }

            captureRequested = false
            captureInProgress = false
        }

        super.onDestroy()
    }

    /**
     * Проверяет, есть ли ожидающий запрос,
     * и запускает screenshot ровно один раз.
     */
    private fun startRequestedCapture() {

        synchronized(YaService::class.java) {

            if (!captureRequested) {
                Log.d(
                    TAG,
                    "startRequestedCapture(): запросов нет"
                )
                return
            }

            if (captureInProgress) {
                Log.d(
                    TAG,
                    "startRequestedCapture(): screenshot уже выполняется"
                )
                return
            }

            /*
             * Сразу переводим состояние:
             *
             * REQUESTED → IN PROGRESS
             *
             * Поэтому даже если requestCapture()
             * будет вызван ещё раз, второй screenshot
             * не запустится.
             */
            captureRequested = false
            captureInProgress = true
        }

        captureScreen()
    }

    /**
     * Делает ОДИН screenshot.
     *
     * ВАЖНО:
     * Яндекс ещё НЕ запущен в этот момент.
     *
     * Сначала полностью получаем и обрабатываем
     * исходный экран.
     */
    private fun captureScreen() {

        Log.d(
            TAG,
            "================================================"
        )

        Log.d(
            TAG,
            "НАЧАЛО SCREENSHOT"
        )

        Log.d(
            TAG,
            "Яндекс ещё НЕ запущен"
        )

        try {

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(
                        screenshot: ScreenshotResult
                    ) {

                        Log.d(
                            TAG,
                            "ScreenshotResult успешно получен"
                        )

                        try {

                            /*
                             * 1. HardwareBuffer → Bitmap
                             */
                            val bitmap =
                                screenshotToBitmap(screenshot)

                            if (bitmap == null) {

                                Log.e(
                                    TAG,
                                    "Не удалось преобразовать screenshot в Bitmap"
                                )

                                showError(
                                    "Не удалось обработать скриншот"
                                )

                                return
                            }

                            Log.d(
                                TAG,
                                "Bitmap получен: " +
                                    "${bitmap.width}x${bitmap.height}"
                            )

                            /*
                             * 2. Bitmap → JPEG
                             */
                            val imageBytes =
                                bitmapToJpeg(bitmap)

                            bitmap.recycle()

                            if (imageBytes == null) {

                                Log.e(
                                    TAG,
                                    "Не удалось создать JPEG"
                                )

                                showError(
                                    "Не удалось создать JPEG"
                                )

                                return
                            }

                            Log.d(
                                TAG,
                                "JPEG готов: ${imageBytes.size} bytes"
                            )

                            /*
                             * 3. И ТОЛЬКО ЗДЕСЬ начинаем
                             * передачу изображения в Яндекс.
                             *
                             * До этого момента Яндекс
                             * вообще не запускался.
                             */
                            sendImageToYandex(imageBytes)

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Ошибка обработки screenshot",
                                e
                            )

                            showError(
                                "Ошибка обработки скриншота"
                            )

                        } finally {

                            /*
                             * Захват полностью завершён.
                             *
                             * ВАЖНО:
                             * здесь НЕ вызывается captureScreen()
                             * и НЕ создаётся новый запрос.
                             */
                            synchronized(YaService::class.java) {
                                captureInProgress = false
                            }

                            Log.d(
                                TAG,
                                "SCREENSHOT ЗАВЕРШЁН"
                            )

                            Log.d(
                                TAG,
                                "================================================"
                            )
                        }
                    }

                    override fun onFailure(
                        errorCode: Int
                    ) {

                        Log.e(
                            TAG,
                            "takeScreenshot() завершился ошибкой. " +
                                "Код: $errorCode"
                        )

                        showError(
                            "Ошибка создания скриншота (код $errorCode)"
                        )

                        synchronized(YaService::class.java) {
                            captureInProgress = false
                        }

                        Log.d(
                            TAG,
                            "SCREENSHOT ЗАВЕРШЁН С ОШИБКОЙ"
                        )
                    }
                }
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Исключение при вызове takeScreenshot()",
                e
            )

            synchronized(YaService::class.java) {
                captureInProgress = false
            }

            showError(
                "Не удалось сделать скриншот: ${e.message}"
            )
        }
    }

    /**
     * HardwareBuffer → обычный ARGB_8888 Bitmap.
     */
    private fun screenshotToBitmap(
        screenshot: ScreenshotResult
    ): Bitmap? {

        val hardwareBuffer: HardwareBuffer =
            screenshot.hardwareBuffer
                ?: run {
                    Log.e(
                        TAG,
                        "ScreenshotResult.hardwareBuffer == null"
                    )
                    return null
                }

        return try {

            val hardwareBitmap =
                Bitmap.wrapHardwareBuffer(
                    hardwareBuffer,
                    screenshot.colorSpace
                )

            if (hardwareBitmap == null) {
                Log.e(
                    TAG,
                    "Bitmap.wrapHardwareBuffer() вернул null"
                )
                return null
            }

            /*
             * Копируем Hardware Bitmap в обычный Bitmap,
             * потому что его можно безопасно сжать в JPEG.
             */
            val softwareBitmap =
                hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            hardwareBitmap.recycle()

            if (softwareBitmap == null) {
                Log.e(
                    TAG,
                    "Не удалось скопировать Hardware Bitmap"
                )
            }

            softwareBitmap

        } finally {

            /*
             * HardwareBuffer больше не нужен.
             */
            try {
                hardwareBuffer.close()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Не удалось закрыть HardwareBuffer",
                    e
                )
            }
        }
    }

    /**
     * Bitmap → JPEG byte array.
     */
    private fun bitmapToJpeg(
        bitmap: Bitmap
    ): ByteArray? {

        val outputStream =
            ByteArrayOutputStream()

        return try {

            val success =
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    outputStream
                )

            if (!success) {

                Log.e(
                    TAG,
                    "Bitmap.compress() вернул false"
                )

                null

            } else {

                outputStream.toByteArray()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Ошибка JPEG compression",
                e
            )

            null

        } finally {

            try {
                outputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Сохраняет полученный screenshot и передаёт его
     * приложению Яндекс.
     *
     * На момент вызова этой функции screenshot уже
     * полностью получен.
     */
    private fun sendImageToYandex(
        imageBytes: ByteArray
    ) {

        var imageFile: File? = null

        try {

            /*
             * Создаём собственную директорию
             * внутри cache приложения.
             */
            val cacheDirectory =
                File(
                    cacheDir,
                    "yacts_images"
                )

            if (!cacheDirectory.exists()) {

                if (!cacheDirectory.mkdirs() &&
                    !cacheDirectory.exists()
                ) {

                    throw IllegalStateException(
                        "Не удалось создать директорию cache"
                    )
                }
            }

            /*
             * Уникальное имя файла.
             */
            imageFile =
                File(
                    cacheDirectory,
                    "yacts_${System.currentTimeMillis()}.jpg"
                )

            /*
             * Сохраняем JPEG.
             */
            imageFile.outputStream().use { output ->
                output.write(imageBytes)
                output.flush()
            }

            Log.d(
                TAG,
                "Изображение сохранено: " +
                    imageFile.absolutePath
            )

            /*
             * Получаем content:// URI через FileProvider.
             */
            val imageUri: Uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    imageFile
                )

            Log.d(
                TAG,
                "Получен URI: $imageUri"
            )

            /*
             * Формируем Intent.
             *
             * Яндекс запускается только сейчас,
             * после завершения screenshot.
             */
            val intent =
                Intent(Intent.ACTION_SEND).apply {

                    type = "image/jpeg"

                    putExtra(
                        Intent.EXTRA_STREAM,
                        imageUri
                    )

                    setPackage(
                        YANDEX_PACKAGE
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            /*
             * Проверяем, существует ли обработчик.
             */
            val resolvedActivity =
                intent.resolveActivity(
                    packageManager
                )

            if (resolvedActivity == null) {

                Log.e(
                    TAG,
                    "Яндекс не может принять ACTION_SEND " +
                        "с image/jpeg"
                )

                showError(
                    "Не удалось найти приложение Яндекс. " +
                        "Проверьте, что оно установлено и обновлено."
                )

                imageFile.delete()

                return
            }

            /*
             * Явно выдаём Яндексу доступ к content:// URI.
             */
            try {

                grantUriPermission(
                    YANDEX_PACKAGE,
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                Log.d(
                    TAG,
                    "URI permission выдан Яндексу"
                )

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Не удалось явно выдать URI permission",
                    e
                )
            }

            Log.d(
                TAG,
                "------------------------------------------------"
            )

            Log.d(
                TAG,
                "ОТКРЫВАЕМ ЯНДЕКС"
            )

            Log.d(
                TAG,
                "Это первый запуск Яндекса после screenshot"
            )

            Log.d(
                TAG,
                "Передаваемый URI: $imageUri"
            )

            Log.d(
                TAG,
                "------------------------------------------------"
            )

            /*
             * ЕДИНСТВЕННЫЙ startActivity() для Яндекса.
             */
            startActivity(intent)

            Log.d(
                TAG,
                "Яндекс успешно запущен"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Ошибка отправки изображения в Яндекс",
                e
            )

            showError(
                "Ошибка отправки изображения в Яндекс: " +
                    (e.message ?: "неизвестная ошибка")
            )

            /*
             * Если файл уже успел создаться,
             * пытаемся удалить его при ошибке.
             */
            try {
                imageFile?.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Показывает ошибку пользователю.
     */
    private fun showError(
        message: String
    ) {

        try {

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Не удалось показать Toast",
                e
            )
        }
    }
}
