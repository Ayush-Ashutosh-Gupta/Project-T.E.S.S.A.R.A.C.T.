package com.example.tessaract

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Project T.E.S.S.A.R.A.C.T. - Google Circle to Search System
 * Allows user to circle or select any region on their screen with glowing neon lasso,
 * crops that exact spatial bounding box, saves it to the app storage directory,
 * performs localized text scraping/OCR from accessibility metadata,
 * and allows instant Google Search, text copy, or read-aloud.
 */
class TessaractCircleToSearchActivity : Activity() {

    companion object {
        private const val TAG = "CircleToSearch"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 3001

        fun launch(context: Context) {
            val intent = Intent(context, TessaractCircleToSearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    private var sourceBitmap: Bitmap? = null
    private var rootFrameLayout: FrameLayout? = null
    private var selectionView: CircleSelectionView? = null
    private var resultBottomSheet: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pendingCircledRect: RectF? = null
    private var pendingCircledPath: Path? = null
    private var isProjectionCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity draw over status bar / nav bar
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        rootFrameLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#44000000")) // Subtle translucent scrim
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Interactive Drawing Canvas
        selectionView = CircleSelectionView(this) { rect, path ->
            onAreaCircled(rect, path)
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootFrameLayout?.addView(selectionView)

        // 2. Top Instruction Pill
        val topBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6080D1A"))
                setStroke(dpToPx(1.5f), Color.parseColor("#8000E5FF"))
                cornerRadius = dpToPx(24).toFloat()
            }
            setPadding(dpToPx(18), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(48)
            }
        }

        val bannerText = TextView(this).apply {
            text = "⭕ Circle any area on screen for AI Processing"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        topBanner.addView(bannerText)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dpToPx(12), dpToPx(4), dpToPx(6), dpToPx(4))
            setOnClickListener {
                finish()
            }
        }
        topBanner.addView(closeBtn)

        rootFrameLayout?.addView(topBanner)
        setContentView(rootFrameLayout)

        // Capture frozen screen frame via MediaProjection API or Direct Screenshot
        captureScreenFrame()
    }

    private fun captureScreenFrame() {
        if (TessaractAccessibilityService.canTakeScreenshotDirectly()) {
            TessaractAccessibilityService.captureScreenshot(
                onSuccess = { bmp ->
                    sourceBitmap = bmp
                    selectionView?.setBackdropBitmap(bmp)
                    checkPendingCircledCrop()
                },
                onError = { err ->
                    Log.w(TAG, "Accessibility direct capture failed: $err. Requesting MediaProjection.")
                    requestMediaProjectionCapture()
                }
            )
        } else {
            requestMediaProjectionCapture()
        }
    }

    private fun requestMediaProjectionCapture() {
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val captureIntent = projectionManager?.createScreenCaptureIntent()
        if (captureIntent != null) {
            startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION)
        } else {
            fallbackDisplayCapture()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startMediaProjectionProcess(resultCode, data)
            } else {
                Log.w(TAG, "MediaProjection permission denied by user. Falling back to display buffer.")
                fallbackDisplayCapture()
            }
        }
    }

    private fun startMediaProjectionProcess(resultCode: Int, data: Intent) {
        if (isProjectionCapturing) return
        isProjectionCapturing = true

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
                    Log.i(TAG, "MediaProjection session ended")
                }
            }, mainHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TessaractCircleOverlay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            var frameAcquired = false
            fun onImageReady(reader: ImageReader) {
                if (frameAcquired) return
                val img = reader.acquireLatestImage() ?: return
                frameAcquired = true
                try {
                    val planes = img.planes
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
                    img.close()

                    val cleanBmp = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    sourceBitmap = cleanBmp
                    selectionView?.setBackdropBitmap(cleanBmp)
                    checkPendingCircledCrop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring MediaProjection bitmap: ${e.message}")
                } finally {
                    cleanUpProjection()
                }
            }

            imageReader?.setOnImageAvailableListener({ reader ->
                onImageReady(reader)
            }, mainHandler)

            // Watchdog in case image listener is slow to emit
            mainHandler.postDelayed({
                if (!frameAcquired) {
                    imageReader?.let { onImageReady(it) }
                }
            }, 300L)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VirtualDisplay for screen capture: ${e.message}")
            cleanUpProjection()
            fallbackDisplayCapture()
        }
    }

    private fun cleanUpProjection() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {}
        finally {
            TessaractService.disableProjectionMode(this)
            isProjectionCapturing = false
        }
    }

    private fun fallbackDisplayCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#121824"))
            sourceBitmap = bmp
            selectionView?.setBackdropBitmap(bmp)
            checkPendingCircledCrop()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fallback canvas: ${e.message}")
        }
    }

    private fun checkPendingCircledCrop() {
        val rect = pendingCircledRect
        val path = pendingCircledPath
        if (rect != null && path != null) {
            pendingCircledRect = null
            pendingCircledPath = null
            onAreaCircled(rect, path)
        }
    }

    private fun onAreaCircled(rect: RectF, path: Path) {
        if (sourceBitmap == null) {
            // Buffer the drawn gesture while screen capture completes
            pendingCircledRect = rect
            pendingCircledPath = path
            return
        }

        val width = (rect.width()).toInt().coerceAtLeast(32)
        val height = (rect.height()).toInt().coerceAtLeast(32)
        val left = rect.left.toInt().coerceAtLeast(0)
        val top = rect.top.toInt().coerceAtLeast(0)

        val bmp = sourceBitmap
        // User requested: "not just the whole screen only the selected area"
        val cropped = if (bmp != null) {
            val safeWidth = min(width, bmp.width - left)
            val safeHeight = min(height, bmp.height - top)
            if (safeWidth > 10 && safeHeight > 10) {
                try {
                    // 1. Create transparent bitmap of the exact selected bounding size
                    val croppedBmp = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(croppedBmp)

                    // 2. Create the circular/lasso path mask translated to local coordinates
                    val maskPath = Path(path).apply {
                        offset(-left.toFloat(), -top.toFloat())
                    }

                    // 3. Clip canvas to the user's circular area so ONLY the selected pixels are retained
                    canvas.clipPath(maskPath)
                    val srcRect = Rect(left, top, left + safeWidth, top + safeHeight)
                    val dstRect = Rect(0, 0, safeWidth, safeHeight)
                    canvas.drawBitmap(bmp, srcRect, dstRect, null)

                    croppedBmp
                } catch (e: Exception) {
                    // Fallback to bounding box crop
                    Bitmap.createBitmap(bmp, left, top, safeWidth, safeHeight)
                }
            } else null
        } else null

        // 1. Save Cropped Image to App Screenshots Directory
        val savedFile = saveCroppedScreenshot(cropped)

        // 2. Scrape localized text within this bounding box
        val extractedText = extractTextInBounds(left, top, left + width, top + height)

        // 3. Trigger AI Processing & show Google Circle to Search Result Bottom Sheet
        showResultSheet(cropped, savedFile, extractedText, left, top, width, height)
    }

    private fun saveCroppedScreenshot(croppedBmp: Bitmap?): File? {
        val bmp = croppedBmp ?: return null
        return try {
            val dir = getExternalFilesDir("screenshots") ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "tessaract_circle_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Circle-to-search cropped screenshot saved to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving cropped screenshot: ${e.message}")
            null
        }
    }

    private fun extractTextInBounds(left: Int, top: Int, right: Int, bottom: Int): String {
        val textSnippets = mutableListOf<String>()
        try {
            val jsonStr = TessaractAccessibilityService.scrapeActiveScreenText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val text = obj.optString("text")
                val boundsStr = obj.optString("bounds") // format: "[left,top,right,bottom]"
                if (boundsStr.startsWith("[") && boundsStr.endsWith("]")) {
                    val parts = boundsStr.removeSurrounding("[", "]").split(",")
                    if (parts.size == 4) {
                        val nodeLeft = parts[0].toIntOrNull() ?: 0
                        val nodeTop = parts[1].toIntOrNull() ?: 0
                        val nodeRight = parts[2].toIntOrNull() ?: 0
                        val nodeBottom = parts[3].toIntOrNull() ?: 0

                        // Check intersection with circled rect
                        val intersects = !(nodeRight < left || nodeLeft > right || nodeBottom < top || nodeTop > bottom)
                        if (intersects && text.isNotBlank()) {
                            textSnippets.add(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error filtering text in circled bounds: ${e.message}")
        }

        return if (textSnippets.isNotEmpty()) {
            textSnippets.distinct().joinToString(" ")
        } else {
            "Selected Region (${right - left}x${bottom - top}px at [$left, $top])"
        }
    }

    private fun showResultSheet(
        croppedBmp: Bitmap?,
        file: File?,
        text: String,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ) {
        resultBottomSheet?.let { rootFrameLayout?.removeView(it) }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2080D1A")) // Frosted Obsidian
                setStroke(dpToPx(1.5f), Color.parseColor("#8000E5FF"))
                cornerRadii = floatArrayOf(
                    dpToPx(24).toFloat(), dpToPx(24).toFloat(),
                    dpToPx(24).toFloat(), dpToPx(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
        }

        // Top Drag Handle
        val dragHandle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#455A64"))
                cornerRadius = dpToPx(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(12)
            }
        }
        sheet.addView(dragHandle)

        // Header Title
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
        }

        val titleView = TextView(this).apply {
            setText("🤖 AI SCREEN CROP & VISUAL ANALYSIS")
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerRow.addView(titleView)

        val dismissBtn = TextView(this).apply {
            setText("Done ✕")
            setTextColor(Color.parseColor("#90A4AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener {
                finish()
            }
        }
        headerRow.addView(dismissBtn)
        sheet.addView(headerRow)

        // Content Row: Cropped Thumbnail + Text info
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(14)
            }
        }

        if (croppedBmp != null) {
            val thumb = ImageView(this).apply {
                setImageBitmap(croppedBmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(dpToPx(1f), Color.parseColor("#00E5FF"))
                    cornerRadius = dpToPx(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(80), dpToPx(80)).apply {
                    marginEnd = dpToPx(12)
                }
            }
            contentRow.addView(thumb)
        }

        val textScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(80), 1.0f)
        }
        val textDisplay = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2600E5FF"))
                cornerRadius = dpToPx(8).toFloat()
            }
        }
        textScroll.addView(textDisplay)
        contentRow.addView(textScroll)
        sheet.addView(contentRow)

        // File Location Pill
        if (file != null) {
            val filePill = TextView(this).apply {
                this.text = "💾 Saved to: ${file.name}"
                setTextColor(Color.parseColor("#00E676"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                }
            }
            sheet.addView(filePill)
        }

        // Action Buttons Row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Copy Text Button
        val copyBtn = TextView(this).apply {
            this.text = "📋 Copy Text"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#37474F"))
                cornerRadius = dpToPx(16).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Tessaract Circle Scrape", text))
                Toast.makeText(this@TessaractCircleToSearchActivity, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        actionsRow.addView(copyBtn)

        // 2. Google Search Button
        val searchBtn = TextView(this).apply {
            this.text = "🔍 Search Google"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = dpToPx(16).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                val query = text.take(120)
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
                finish()
            }
        }
        actionsRow.addView(searchBtn)

        // 3. Read Aloud Button
        val readBtn = TextView(this).apply {
            this.text = "🔊 Read"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7C4DFF"))
                cornerRadius = dpToPx(16).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener {
                val engine = AcousticCalibrationEngine(this@TessaractCircleToSearchActivity)
                engine.speak(text)
            }
        }
        actionsRow.addView(readBtn)
        sheet.addView(actionsRow)

        // 4. Ask Nemotron AI Button (Nvidia Nemotron on Nebius Token Factory)
        val nemotronBtn = TextView(this).apply {
            this.text = "⚡ Ask Nvidia Nemotron AI"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676")) // Neon Green
                cornerRadius = dpToPx(16).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            setOnClickListener {
                if (!NebiusNemotronClient.isConfigured(this@TessaractCircleToSearchActivity)) {
                    Toast.makeText(
                        this@TessaractCircleToSearchActivity,
                        "Configure your Nebius API Key in Settings > Commands & AI for Nemotron AI",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                this.text = "⏳ Querying Nvidia Nemotron on Nebius..."
                this.isEnabled = false
                textDisplay.text = "⚡ Sending screen context to Nvidia Nemotron on Nebius Cloud..."

                CoroutineScope(Dispatchers.IO).launch {
                    val prompt = "Analyze this text extracted from the user's mobile screen selection and provide a helpful, concise summary or answer:\n\n$text"
                    val aiResponse = NebiusNemotronClient.queryNemotron(this@TessaractCircleToSearchActivity, prompt)
                    withContext(Dispatchers.Main) {
                        textDisplay.text = "⚡ NEMOTRON AI RESPONSE:\n\n$aiResponse"
                        this@apply.text = "✓ Nemotron Analysis Complete"
                        this@apply.isEnabled = true
                    }
                }
            }
        }
        sheet.addView(nemotronBtn)

        resultBottomSheet = sheet
        rootFrameLayout?.addView(sheet)
    }

    private fun dpToPx(dp: Float): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanUpProjection()
    }
}

/**
 * CircleSelectionView
 * Interactive touch canvas that tracks user's finger gesture,
 * draws glowing neon laser loops, and determines the bounded rectangle.
 */
class CircleSelectionView(
    context: Context,
    private val onCircleComplete: (RectF, Path) -> Unit
) : View(context) {

    private var backdropBitmap: Bitmap? = null
    private val path = Path()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyber Cyan laser
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2600E5FF")
        style = Paint.Style.FILL
    }
    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = Float.MIN_VALUE
    private var maxY = Float.MIN_VALUE
    private var isDrawing = false
    private var isSelected = false
    private val selectedRect = RectF()

    fun setBackdropBitmap(bmp: Bitmap) {
        backdropBitmap = bmp
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isSelected = false
                isDrawing = true
                path.reset()
                path.moveTo(x, y)
                minX = x
                minY = y
                maxX = x
                maxY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    path.lineTo(x, y)
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    path.close()
                    isDrawing = false

                    // If user only tapped a single point, expand to standard 140dp search box
                    val density = resources.displayMetrics.density
                    if ((maxX - minX) < 24f * density && (maxY - minY) < 24f * density) {
                        minX = (x - 70f * density).coerceAtLeast(0f)
                        minY = (y - 70f * density).coerceAtLeast(0f)
                        maxX = (x + 70f * density).coerceAtMost(width.toFloat())
                        maxY = (y + 70f * density).coerceAtMost(height.toFloat())
                        path.reset()
                        path.addRect(minX, minY, maxX, maxY, Path.Direction.CW)
                    }

                    selectedRect.set(minX, minY, maxX, maxY)
                    isSelected = true
                    invalidate()

                    onCircleComplete(selectedRect, path)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw frozen frame backdrop if available
        backdropBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }

        // 2. Draw Lasso Path
        if (!path.isEmpty) {
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, drawPaint)
            if (isSelected) {
                canvas.drawPath(path, fillPaint)
                canvas.drawRect(selectedRect, boxBorderPaint)
            }
        }
    }
}
