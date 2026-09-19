package ru.yacts.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private const val ACTION_CAPTURE = "ru.yacts.app.CAPTURE"

class MainActivity : Activity() {

    private var waitingForAccessibility = false

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

        if (waitingForAccessibility && isAccessibilityEnabled()) {
            requestCapture()
        }
    }

    private fun requestCapture() {
        waitingForAccessibility = false

        Log.e("YaCTS_TEST", "Requesting screenshot")

        YaService.requestCapture()

        Handler(Looper.getMainLooper()).postDelayed({
            YaService.requestCapture()
        }, 1000)

        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${YaService::class.java.name}"

        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return raw.split(':').any {
            it.equals(expected, ignoreCase = true)
        }
    }

    private fun showSetupScreen() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#101216"))
        root.setPadding(48, 80, 48, 80)

        val description = TextView(this)
        description.text =
            "YaCTS\n\n" +
            "Один раз включите спец. возможности:\n\n" +
            "1. Нажмите «Открыть настройки»\n" +
            "2. Найдите «YaCTS» в списке\n" +
            "3. Включите и подтвердите\n" +
            "4. Вернитесь сюда"

        description.setTextColor(Color.WHITE)
        description.textSize = 18f

        val button = Button(this)
        button.text = "Открыть настройки"

        button.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        root.addView(description)
        root.addView(button)

        setContentView(root)
    }
}


class YaService : AccessibilityService() {

    private var isCapturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.e("YaCTS_TEST", "=== ACCESSIBILITY SERVICE CONNECTED ===")

        if (captureRequested) {
            captureRequested = false

            Handler(Looper.getMainLooper()).postDelayed({
                captureScreen()
            }, 300)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не используется.
    }

    override fun onInterrupt() {
        // Не используется.
    }

    private fun captureScreen() {
        if (isCapturing) {
            Log.e("YaCTS_TEST", "Capture already running")
            return
        }

        isCapturing = true

        Log.e("YaCTS_TEST", "=== SCREENSHOT REQUEST START ===")

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(result: ScreenshotResult) {
                        Log.e("YaCTS_TEST", "=== SCREENSHOT SUCCESS ===")

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
                                    "YaCTS_TEST",
                                    "Hardware bitmap is null"
                                )
                                isCapturing = false
                                return
                            }

                            val bitmap =
                                hardwareBitmap.copy(
                                    Bitmap.Config.ARGB_8888,
                                    false
                                )

                            hardwareBitmap.recycle()

                            if (bitmap == null) {
                                Log.e(
                                    "YaCTS_TEST",
                                    "Software bitmap is null"
                                )
                                isCapturing = false
                                return
                            }

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

                            Log.e(
                                "YaCTS_TEST",
                                "Screenshot converted: ${imageBytes.size} bytes"
                            )

                            sendImageToYandex(imageBytes)

                        } catch (t: Throwable) {
                            Log.e(
                                "YaCTS_TEST",
                                "Screenshot processing error",
                                t
                            )
                        } finally {
                            isCapturing = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isCapturing = false

                        Log.e(
                            "YaCTS_TEST",
                            "=== SCREENSHOT FAILURE: $errorCode ==="
                        )
                    }
                }
            )

        } catch (t: Throwable) {
            isCapturing = false

            Log.e(
                "YaCTS_TEST",
                "takeScreenshot threw exception",
                t
            )
        }
    }

    private fun sendImageToYandex(imageBytes: ByteArray) {
        try {
            Log.e(
                "YaCTS_TEST",
                "Preparing image for com.yandex.searchapp"
            )

            val file = File(
                cacheDir,
                "yacts_screenshot.jpg"
            )

            file.writeBytes(imageBytes)

            Log.e(
                "YaCTS_TEST",
                "Screenshot saved: ${file.absolutePath}"
            )

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            Log.e(
                "YaCTS_TEST",
                "Content URI: $uri"
            )

            val intent = Intent(Intent.ACTION_SEND)

            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)

            intent.setPackage("com.yandex.searchapp")

            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            val resolved =
                intent.resolveActivity(packageManager)

            if (resolved == null) {
                Log.e(
                    "YaCTS_TEST",
                    "!!! com.yandex.searchapp DOES NOT ACCEPT ACTION_SEND image/jpeg !!!"
                )
                return
            }

            Log.e(
                "YaCTS_TEST",
                "Resolved activity: $resolved"
            )

            grantUriPermission(
                "com.yandex.searchapp",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            Log.e(
                "YaCTS_TEST",
                "=== SENDING IMAGE TO YANDEX ==="
            )

            startActivity(intent)

            Log.e(
                "YaCTS_TEST",
                "=== IMAGE SENT TO YANDEX ==="
            )

        } catch (t: Throwable) {
            Log.e(
                "YaCTS_TEST",
                "!!! ERROR SENDING IMAGE TO YANDEX !!!",
                t
            )
        }
    }

    companion object {

        private var instance: YaService? = null

        private var captureRequested = false

        fun requestCapture() {
            Log.e(
                "YaCTS_TEST",
                "YaService.requestCapture()"
            )

            val service = instance

            if (service != null) {
                Log.e(
                    "YaCTS_TEST",
                    "Service instance exists"
                )

                Handler(Looper.getMainLooper()).postDelayed({
                    service.captureScreen()
                }, 100)

            } else {
                Log.e(
                    "YaCTS_TEST",
                    "Service instance is NULL - waiting for connection"
                )

                captureRequested = true
            }
        }
    }
}
