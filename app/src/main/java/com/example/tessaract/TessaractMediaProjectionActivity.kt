package com.example.tessaract

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Headless translucent Activity to acquire MediaProjection permission token
 * and capture a visual frame.
 */
class TessaractMediaProjectionActivity : Activity() {

    interface FrameCaptureCallback {
        fun onSuccess(jpegBytes: ByteArray)
        fun onError(errorCode: String, description: String)
    }

    companion object {
        private const val TAG = "MediaProjectionAct"
        private const val REQUEST_CODE_CAPTURE = 1001

        private var activeCallback: FrameCaptureCallback? = null

        fun requestScreenCapture(context: Context, callback: FrameCaptureCallback) {
            activeCallback = callback
            val intent = Intent(context, TessaractMediaProjectionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

        if (projectionManager == null) {
            activeCallback?.onError("ERR_NO_PROJECTION_MGR", "MediaProjection service unavailable")
            finish()
            return
        }

        val captureIntent = projectionManager?.createScreenCaptureIntent()
        if (captureIntent != null) {
            startActivityForResult(captureIntent, REQUEST_CODE_CAPTURE)
        } else {
            activeCallback?.onError("ERR_INTENT_NULL", "Cannot generate screen capture intent")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                processCapture(resultCode, data)
            } else {
                activeCallback?.onError("ERR_USER_CANCELLED", "User denied projection prompt")
                finish()
            }
        }
    }

    private fun processCapture(resultCode: Int, data: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            TessaractService.enableProjectionMode(this)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection = projectionManager?.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                }
            }, handler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TessaractCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            var frameProcessed = false
            fun extractBitmapFromImage(image: android.media.Image) {
                if (frameProcessed) {
                    image.close()
                    return
                }
                frameProcessed = true
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val stream = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val bytes = stream.toByteArray()

                    activeCallback?.onSuccess(bytes)
                } catch (e: Exception) {
                    activeCallback?.onError("ERR_PROCESSING", e.message ?: "Failed copying buffer")
                } finally {
                    cleanUp()
                    finish()
                }
            }

            imageReader?.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage()
                if (img != null) {
                    extractBitmapFromImage(img)
                }
            }, handler)

            var attempts = 0
            val maxAttempts = 15
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (frameProcessed) return
                    try {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            extractBitmapFromImage(image)
                        } else {
                            attempts++
                            if (attempts < maxAttempts) {
                                handler.postDelayed(this, 150L)
                            } else {
                                activeCallback?.onError("ERR_TIMEOUT", "Display frame capture timed out")
                                cleanUp()
                                finish()
                            }
                        }
                    } catch (e: Exception) {
                        activeCallback?.onError("ERR_PROCESSING", e.message ?: "Unknown error in buffer copy")
                        cleanUp()
                        finish()
                    }
                }
            }
            handler.postDelayed(pollRunnable, 200L)

        } catch (e: Exception) {
            activeCallback?.onError("ERR_SETUP", e.message ?: "Virtual display setup failed")
            cleanUp()
            finish()
        }
    }

    private fun cleanUp() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up projection: ${e.message}")
        } finally {
            TessaractService.disableProjectionMode(this)
        }
    }
}
