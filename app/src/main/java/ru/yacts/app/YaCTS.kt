package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "YaCTS"

private const val YANDEX_PACKAGE = "com.yandex.searchapp"

class MainActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAccessibilityEnabled()) {
            requestCapture()
        } else {
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось открыть настройки Accessibility", e)
            }
        }

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

    private fun requestCapture() {
        YaService.requestCapture()

        // Небольшая задержка нужна на случай, если AccessibilityService
        // ещё не успел получить управление.
        Handler(Looper.getMainLooper()).postDelayed({
            YaService.requestCapture()
        }, 1000)
    }
}


class YaService : AccessibilityService() {

    companion object {

        private var instance: YaService? = null

        @Volatile
        private var captureRequested = false

        @Volatile
        private var captureInProgress = false

        fun requestCapture() {
            captureRequested = true

            val service = instance

            if (service != null) {
                service.captureScreen()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.d(TAG, "AccessibilityService подключён")

        if (captureRequested) {
            captureScreen()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Нам не нужны события интерфейса.
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService прерван")
    }

    override fun onDestroy() {
        super.onDestroy()

        if (instance === this) {
            instance = null
        }

        captureInProgress = false

        Log.d(TAG, "AccessibilityService уничтожен")
    }

    private fun captureScreen() {

        if (captureInProgress) {
            Log.d(TAG, "Скриншот уже создаётся")
            return
        }

        if (!captureRequested) {
            return
        }

        captureRequested = false
        captureInProgress = true

        Log.d(TAG, "Начинаем создание скриншота")

        try {

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(
                        screenshot: ScreenshotResult
                    ) {

                        Log.d(TAG, "Скриншот получен")

                        try {

                            val bitmap =
                                screenshotToBitmap(screenshot)

                            if (bitmap == null) {
                                Log.e(
                                    TAG,
                                    "Не удалось преобразовать скриншот в Bitmap"
                                )

                                Toast.makeText(
                                    this@YaService,
                                    "Не удалось обработать скриншот",
                                    Toast.LENGTH_LONG
                                ).show()

                                captureInProgress = false
                                return
                            }

                            val imageBytes =
                                bitmapToJpeg(bitmap)

                            bitmap.recycle()

                            if (imageBytes == null) {
                                Log.e(
                                    TAG,
                                    "Не удалось создать JPEG"
                                )

                                Toast.makeText(
                                    this@YaService,
                                    "Не удалось создать JPEG",
                                    Toast.LENGTH_LONG
                                ).show()

                                captureInProgress = false
                                return
                            }

                            Log.d(
                                TAG,
                                "JPEG готов: ${imageBytes.size} bytes"
                            )

                            sendImageToYandex(imageBytes)

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Ошибка обработки скриншота",
                                e
                            )

                        } finally {
                            captureInProgress = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {

                        Log.e(
                            TAG,
                            "Ошибка screenshot. Код: $errorCode"
                        )

                        Toast.makeText(
                            this@YaService,
                            "Ошибка создания скриншота (код $errorCode)",
                            Toast.LENGTH_LONG
                        ).show()

                        captureInProgress = false
                    }
                }
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Ошибка вызова takeScreenshot",
                e
            )

            captureInProgress = false
        }
    }

    private fun screenshotToBitmap(
        screenshot: ScreenshotResult
    ): Bitmap? {

        val hardwareBuffer =
            screenshot.hardwareBuffer ?: return null

        val colorSpace =
            screenshot.colorSpace

        val hardwareBitmap =
            Bitmap.wrapHardwareBuffer(
                hardwareBuffer,
                colorSpace
            )

        hardwareBuffer.close()

        if (hardwareBitmap == null) {
            return null
        }

        val softwareBitmap =
            hardwareBitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )

        hardwareBitmap.recycle()

        return softwareBitmap
    }

    private fun bitmapToJpeg(
        bitmap: Bitmap
    ): ByteArray? {

        val outputStream =
            ByteArrayOutputStream()

        val success =
            bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                90,
                outputStream
            )

        if (!success) {
            outputStream.close()
            return null
        }

        val bytes =
            outputStream.toByteArray()

        outputStream.close()

        return bytes
    }

    private fun sendImageToYandex(
        imageBytes: ByteArray
    ) {

        try {

            /*
             * Сохраняем изображение во временный файл.
             *
             * Оно НЕ отправляется через HTTP.
             *
             * Файл будет передан приложению Яндекс
             * через Android content:// URI.
             */

            val cacheDirectory =
                File(cacheDir, "yacts_images")

            if (!cacheDirectory.exists()) {
                cacheDirectory.mkdirs()
            }

            val imageFile =
                File(
                    cacheDirectory,
                    "yacts_${System.currentTimeMillis()}.jpg"
                )

            imageFile.outputStream().use { output ->
                output.write(imageBytes)
            }

            Log.d(
                TAG,
                "Изображение сохранено: ${imageFile.absolutePath}"
            )

            val imageUri: Uri =
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    imageFile
                )

            Log.d(
                TAG,
                "URI: $imageUri"
            )

            val intent =
                Intent(Intent.ACTION_SEND).apply {

                    type = "image/jpeg"

                    putExtra(
                        Intent.EXTRA_STREAM,
                        imageUri
                    )

                    setPackage(YANDEX_PACKAGE)

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            /*
             * Проверяем, умеет ли установленное приложение Яндекс
             * принять ACTION_SEND с изображением.
             */

            val packageManager =
                packageManager

            val resolvedActivity =
                intent.resolveActivity(packageManager)

            if (resolvedActivity == null) {

                Log.e(
                    TAG,
                    "Приложение Яндекс не принимает ACTION_SEND image/jpeg " +
                    "(не установлено или недоступно из-за package visibility)"
                )

                Toast.makeText(
                    this,
                    "Не удалось найти приложение Яндекс. " +
                    "Проверьте, что оно установлено и обновлено.",
                    Toast.LENGTH_LONG
                ).show()

                try {
                    imageFile.delete()
                } catch (_: Exception) {
                }

                return
            }

            /*
             * Дополнительно выдаём Яндексу разрешение
             * на чтение content:// URI.
             */

            try {

                grantUriPermission(
                    YANDEX_PACKAGE,
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
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
                "Отправляем изображение в $YANDEX_PACKAGE"
            )

            startActivity(intent)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Ошибка отправки изображения в Яндекс",
                e
            )

            Toast.makeText(
                this,
                "Ошибка отправки изображения в Яндекс: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
