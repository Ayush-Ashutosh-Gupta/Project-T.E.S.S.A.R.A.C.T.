package com.example.tessaract

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.Serializable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Project T.E.S.S.A.R.A.C.T. - Glassmorphic System Voice Overlay
 * Siri / Google Assistant / Bixby style bottom-floating HUD.
 * Features:
 * - Crisp, realistic 4D Hypercube (Tesseract) wireframe with crystal volume & dynamic speech pulse
 * - Solid, vibrant Cyber Cyan & Neon Violet lines (non-diluted, non-messy)
 * - Glassmorphic obsidian container with glowing rim
 * - Live transcript text and assistant response cards
 * - Multi-contact disambiguation interactive selectable cards (say "1" / "2" or tap to call)
 * - Quick action control chips (Click Picture, Screenshot, Read Screen, Torch, Battery, Sleep)
 * - Swipe-down dismissal and direct Screen Access settings opener
 */
class TessaractOverlayService : Service() {

    companion object {
        private const val TAG = "TessaractOverlay"
        const val ACTION_SHOW = "com.example.tessaract.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.example.tessaract.OVERLAY_HIDE"
        const val ACTION_UPDATE = "com.example.tessaract.OVERLAY_UPDATE"
        const val ACTION_UPDATE_RMS = "com.example.tessaract.OVERLAY_UPDATE_RMS"
        const val ACTION_SHOW_DISAMBIGUATION = "com.example.tessaract.OVERLAY_SHOW_DISAMBIGUATION"
        const val ACTION_CLEAR_DISAMBIGUATION = "com.example.tessaract.OVERLAY_CLEAR_DISAMBIGUATION"

        const val EXTRA_TITLE = "extra_overlay_title"
        const val EXTRA_SUBTITLE = "extra_overlay_subtitle"
        const val EXTRA_RMS_DB = "extra_rms_db"
        const val EXTRA_CANDIDATES = "extra_candidates"

        @Volatile var instance: TessaractOverlayService? = null
        var isOverlayShowing: Boolean = false
            private set

        fun show(context: Context, status: String = "Listening for commands...") {
            if (!Settings.canDrawOverlays(context)) return

            // Privacy Exclusion: Never show HUD overlay on sensitive packages (Settings, Banking, UPI, Lockscreen)
            val fgPkg = TessaractAccessibilityService.currentForegroundPackage
            if (TessaractAccessibilityService.isSensitiveApp(fgPkg) || TessaractAccessibilityService.isKeyguardActive(context)) {
                Log.i(TAG, "Overlay suppressed: Sensitive app active ($fgPkg) or keyguard locked")
                return
            }

            val intent = Intent(context, TessaractOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_TITLE, "T.E.S.S.A.R.A.C.T.")
                putExtra(EXTRA_SUBTITLE, status)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error starting overlay service: ${e.message}")
            }
        }

        fun hide(context: Context) {
            val inst = instance
            if (inst != null) {
                inst.removeOverlay()
                inst.stopSelf()
                return
            }
            val intent = Intent(context, TessaractOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {}
        }

        fun updateText(context: Context, title: String, subtitle: String) {
            if (!isOverlayShowing) return
            val inst = instance
            if (inst != null) {
                inst.updateOverlayContent(title, subtitle)
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, TessaractOverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {}
        }

        fun updateRms(context: Context, rmsDb: Float) {
            if (!isOverlayShowing) return
            // Pure in-memory direct view update: zero IPC, zero startService calls!
            instance?.pulsatingTesseractView?.setRmsLevel(rmsDb)
        }

        fun showDisambiguation(
            context: Context,
            title: String,
            subtitle: String,
            candidates: ArrayList<LocalComponentRouter.ContactCandidate>
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, TessaractOverlayService::class.java).apply {
                action = ACTION_SHOW_DISAMBIGUATION
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_CANDIDATES, candidates as Serializable)
            }
            context.startService(intent)
        }

        fun clearDisambiguation(context: Context) {
            if (!isOverlayShowing) return
            val inst = instance
            if (inst != null) {
                inst.disambiguationLayout?.removeAllViews()
                inst.disambiguationLayout?.visibility = View.GONE
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, TessaractOverlayService::class.java).apply {
                action = ACTION_CLEAR_DISAMBIGUATION
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {}
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var pulsatingTesseractView: PulsatingTesseractView? = null
    private var transcriptTextView: TextView? = null
    private var responseTextView: TextView? = null
    private var statusDotView: View? = null
    private var disambiguationLayout: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SHOW
        when (action) {
            ACTION_SHOW -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "T.E.S.S.A.R.A.C.T."
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Listening for commands..."
                initOrUpdateOverlay(title, subtitle)
            }
            ACTION_UPDATE -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "T.E.S.S.A.R.A.C.T."
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: ""
                updateOverlayContent(title, subtitle)
            }
            ACTION_UPDATE_RMS -> {
                val rms = intent?.getFloatExtra(EXTRA_RMS_DB, -50f) ?: -50f
                pulsatingTesseractView?.setRmsLevel(rms)
            }
            ACTION_SHOW_DISAMBIGUATION -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Multiple Contacts"
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Say index or tap a card:"
                @Suppress("UNCHECKED_CAST")
                val candidates = intent?.getSerializableExtra(EXTRA_CANDIDATES) as? ArrayList<LocalComponentRouter.ContactCandidate>
                initOrUpdateOverlay(title, subtitle)
                renderDisambiguationCards(candidates)
            }
            ACTION_CLEAR_DISAMBIGUATION -> {
                disambiguationLayout?.removeAllViews()
                disambiguationLayout?.visibility = View.GONE
            }
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun initOrUpdateOverlay(title: String, subtitle: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlay: permission denied")
            return
        }

        if (overlayContainer == null) {
            createGlassmorphicOverlayView()
        }
        updateOverlayContent(title, subtitle)
    }

    private fun createGlassmorphicOverlayView() {
        val wm = windowManager ?: return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Float above system navigation insets at the bottom center of the display
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dpToPx(24)
        }

        // Outer Root Wrapper
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(14), 0, dpToPx(14), dpToPx(8))
        }

        // Frosted Glass Card Container
        val cardWidth = minOf(resources.displayMetrics.widthPixels - dpToPx(28), dpToPx(388))
        val glassCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2080D1A")) // Frosted obsidian glass
                setStroke(dpToPx(1.5f), Color.parseColor("#6600E5FF")) // Cyber cyan neon border
                cornerRadius = dpToPx(22).toFloat()
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                cardWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Top Header Bar: Status Indicator + Close [✕] Button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusDotView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E676")) // Neon green active
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                marginEnd = dpToPx(8)
            }
        }
        topBar.addView(statusDotView)

        val brandTitleView = TextView(this).apply {
            text = "T.E.S.S.A.R.A.C.T. ASSISTANT"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBar.addView(brandTitleView)

        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#90A4AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
            setOnClickListener {
                val intent = Intent(this@TessaractOverlayService, TessaractService::class.java).apply {
                    action = TessaractService.ACTION_GO_TO_STANDBY
                }
                startService(intent)
            }
        }
        topBar.addView(closeButton)
        glassCard.addView(topBar)

        // Live Voice Transcript Text View
        transcriptTextView = TextView(this).apply {
            text = "Listening for command..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(2)
            }
        }
        glassCard.addView(transcriptTextView)

        // Assistant Response Card / Subtitle
        responseTextView = TextView(this).apply {
            text = "Speak a command or circle any area on screen"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        glassCard.addView(responseTextView)

        // Disambiguation Cards Container (hidden until multiple matches exist)
        disambiguationLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        glassCard.addView(disambiguationLayout)

        // Horizontal Quick Action Chips Row
        val scrollChips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        chipsRow.addView(createChip("⭕ Circle Search", Color.parseColor("#3300E5FF"), Color.parseColor("#00E5FF")) {
            TessaractCircleToSearchActivity.launch(this)
        })

        chipsRow.addView(createChip("🛑 Sleep", Color.parseColor("#33FF5252"), Color.parseColor("#FF5252")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_GO_TO_STANDBY
            }
            startService(intent)
        })

        chipsRow.addView(createChip("📷 Click Picture", Color.parseColor("#2600E5FF"), Color.parseColor("#00E5FF")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_SIMULATE_TRIGGER
                putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "click picture")
            }
            startService(intent)
        })

        chipsRow.addView(createChip("🖼️ Screenshot", Color.parseColor("#2600E5FF"), Color.parseColor("#00E5FF")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_SIMULATE_TRIGGER
                putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "take screenshot")
            }
            startService(intent)
        })

        chipsRow.addView(createChip("👁️ Read Screen", Color.parseColor("#267C4DFF"), Color.parseColor("#B388FF")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_SIMULATE_TRIGGER
                putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "read screen")
            }
            startService(intent)
        })

        chipsRow.addView(createChip("⚙️ Screen Access", Color.parseColor("#26FFAB00"), Color.parseColor("#FFAB00")) {
            val a11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(a11yIntent)
        })

        chipsRow.addView(createChip("🔦 Torch", Color.parseColor("#26FFD600"), Color.parseColor("#FFD600")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_SIMULATE_TRIGGER
                putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "flashlight on")
            }
            startService(intent)
        })

        chipsRow.addView(createChip("🔋 Battery", Color.parseColor("#2600E676"), Color.parseColor("#00E676")) {
            val intent = Intent(this, TessaractService::class.java).apply {
                action = TessaractService.ACTION_SIMULATE_TRIGGER
                putExtra(TessaractService.EXTRA_TRIGGER_PHRASE, "check battery")
            }
            startService(intent)
        })

        scrollChips.addView(chipsRow)
        glassCard.addView(scrollChips)

        // 1. Add Response Glassmorphic Card on top
        rootLayout.addView(glassCard)

        // 2. Revolving Realistic 4D Tesseract Icon (stands alone cleanly at the bottom center)
        pulsatingTesseractView = PulsatingTesseractView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(88), dpToPx(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(4)
                bottomMargin = dpToPx(2)
            }
            setOnClickListener {
                val intent = Intent(this@TessaractOverlayService, TessaractService::class.java).apply {
                    action = TessaractService.ACTION_TRIGGER_VOICE_PROMPT
                }
                startService(intent)
            }
        }
        rootLayout.addView(pulsatingTesseractView)

        // Drag & Swipe Down gesture to sleep
        var initialY = 0
        var initialTouchY = 0f

        glassCard.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (initialTouchY - event.rawY).toInt()
                    // Swipe down dismiss check -> enters standby
                    if (event.rawY - initialTouchY > dpToPx(48)) {
                        val intent = Intent(this, TessaractService::class.java).apply {
                            action = TessaractService.ACTION_GO_TO_STANDBY
                        }
                        startService(intent)
                        return@setOnTouchListener true
                    }
                    params.y = maxOf(dpToPx(8), initialY + dy)
                    wm.updateViewLayout(rootLayout, params)
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(rootLayout, params)
            overlayContainer = rootLayout
            isOverlayShowing = true
            pulsatingTesseractView?.startBreathing()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding glassmorphic overlay: ${e.message}")
        }
    }

    private fun createChip(label: String, bgTint: Int, strokeTint: Int, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(bgTint)
                setStroke(dpToPx(1f), strokeTint)
                cornerRadius = dpToPx(14).toFloat()
            }
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun renderDisambiguationCards(candidates: List<LocalComponentRouter.ContactCandidate>?) {
        val layout = disambiguationLayout ?: return
        layout.removeAllViews()

        if (candidates.isNullOrEmpty()) {
            layout.visibility = View.GONE
            return
        }

        layout.visibility = View.VISIBLE

        val headerText = TextView(this).apply {
            text = "Select a contact (Say 1 or 2, or tap below):"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            maxLines = 1
            setPadding(0, 0, 0, dpToPx(6))
        }
        layout.addView(headerText)

        candidates.forEachIndexed { index, candidate ->
            val cardNumber = index + 1
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#331E2A4A")) // Translucent navy glass
                    setStroke(dpToPx(1f), Color.parseColor("#4D00E5FF"))
                    cornerRadius = dpToPx(12).toFloat()
                }
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(6)
                }
                isClickable = true
                isFocusable = true
            }

            // Number Badge [ 1 ]
            val badgeView = TextView(this).apply {
                text = "$cardNumber"
                setTextColor(Color.parseColor("#080D1A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                maxLines = 1
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#00E5FF")) // Bright cyan badge
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                    marginEnd = dpToPx(10)
                }
            }
            cardView.addView(badgeView)

            // Contact Details Column
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val nameView = TextView(this).apply {
                text = candidate.displayName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(nameView)

            val phoneView = TextView(this).apply {
                text = candidate.phoneNumber
                setTextColor(Color.parseColor("#90CAF9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setSingleLine(true)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(phoneView)

            cardView.addView(textCol)

            // Call Action Indicator
            val actionLabel = TextView(this).apply {
                text = "CALL 📞"
                setTextColor(Color.parseColor("#00E676"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                maxLines = 1
            }
            cardView.addView(actionLabel)
            cardView.addView(actionLabel)

            // Tapping directly calls that candidate
            cardView.setOnClickListener {
                val intent = Intent(this@TessaractOverlayService, TessaractService::class.java).apply {
                    action = TessaractService.ACTION_SELECT_DISAMBIGUATION_INDEX
                    putExtra(TessaractService.EXTRA_DISAMBIGUATION_INDEX, index)
                }
                startService(intent)
            }

            layout.addView(cardView)
        }
    }

    private fun updateOverlayContent(title: String, subtitle: String) {
        val cleanTitle = title.trim()
        val cleanSubtitle = subtitle.trim()

        if (cleanTitle.isNotBlank() && cleanTitle != "T.E.S.S.A.R.A.C.T.") {
            transcriptTextView?.text = cleanTitle
        } else if (cleanSubtitle.contains("Listening", ignoreCase = true) || cleanSubtitle.contains("Hearing", ignoreCase = true)) {
            transcriptTextView?.text = "Listening for command..."
        }

        if (cleanSubtitle.isNotBlank()) {
            responseTextView?.text = cleanSubtitle
            responseTextView?.visibility = View.VISIBLE
        } else {
            responseTextView?.visibility = View.GONE
        }

        if (subtitle.contains("Listening", ignoreCase = true) || subtitle.contains("Hearing", ignoreCase = true)) {
            statusDotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E676")) // Neon Green
            }
            pulsatingTesseractView?.setActiveGlowColor(Color.parseColor("#00E5FF"), Color.parseColor("#00E676"))
        } else if (subtitle.contains("Processing", ignoreCase = true) || subtitle.contains("Executing", ignoreCase = true)) {
            statusDotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFAB00")) // Amber
            }
            pulsatingTesseractView?.setActiveGlowColor(Color.parseColor("#FFAB00"), Color.parseColor("#FF6D00"))
        } else {
            statusDotView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E5FF")) // Cyan
            }
            pulsatingTesseractView?.setActiveGlowColor(Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF"))
        }
    }

    private fun removeOverlay() {
        try {
            pulsatingTesseractView?.stopBreathing()
            if (overlayContainer != null) {
                windowManager?.removeView(overlayContainer)
                overlayContainer = null
                isOverlayShowing = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay: ${e.message}")
        }
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
        instance = null
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * PulsatingTesseractView
 * Clean, Realistic 4D Cosmic Hypercube (Tesseract) Projection & Voice Amplitude Visualizer.
 * Renders an outer concentric cube, an inner hyper-cube with 4D volumetric breathing,
 * 8 structural connecting struts, 16 crystalline vertex nodes, and a pulsating quantum core.
 * Features solid, non-diluted lines with high contrast and smooth 60fps rendering.
 */
class PulsatingTesseractView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 8 Base Cube Normals: (x, y, z) in {-1, 1}
    private val cubeNormals = arrayOf(
        floatArrayOf(-1f, -1f, -1f), // 0: Top Front Left
        floatArrayOf( 1f, -1f, -1f), // 1: Top Front Right
        floatArrayOf( 1f,  1f, -1f), // 2: Bottom Front Right
        floatArrayOf(-1f,  1f, -1f), // 3: Bottom Front Left
        floatArrayOf(-1f, -1f,  1f), // 4: Top Back Left
        floatArrayOf( 1f, -1f,  1f), // 5: Top Back Right
        floatArrayOf( 1f,  1f,  1f), // 6: Bottom Back Right
        floatArrayOf(-1f,  1f,  1f)  // 7: Bottom Back Left
    )

    // 12 Edges for each cube
    private val cubeEdges = arrayOf(
        // Front face (0-1-2-3)
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 0),
        // Back face (4-5-6-7)
        Pair(4, 5), Pair(5, 6), Pair(6, 7), Pair(7, 4),
        // Connecting depth pillars
        Pair(0, 4), Pair(1, 5), Pair(2, 6), Pair(3, 7)
    )

    // 6 Faces for inner cube holographic volume fill
    private val cubeFaces = arrayOf(
        intArrayOf(0, 1, 2, 3),
        intArrayOf(4, 5, 6, 7),
        intArrayOf(0, 1, 5, 4),
        intArrayOf(2, 3, 7, 6),
        intArrayOf(0, 3, 7, 4),
        intArrayOf(1, 2, 6, 5)
    )

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val auraFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val innerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val faceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var primaryGlow = Color.parseColor("#00E5FF")   // Cyber Cyan
    private var secondaryGlow = Color.parseColor("#B388FF") // Electric Violet
    private var coreGlow = Color.parseColor("#7C4DFF")      // Quantum Purple

    private var currentScale = 1.0f
    private var targetScale = 1.0f
    private var rotationAngle = 0f
    private var breathingPhase = 0f
    private var animator: ValueAnimator? = null

    init {
        startBreathing()
    }

    fun setActiveGlowColor(primary: Int, secondary: Int) {
        primaryGlow = primary
        secondaryGlow = secondary
        invalidate()
    }

    fun setRmsLevel(rmsDb: Float) {
        // Map decibels (-55 dB to 0 dB) to dynamic scale factor (1.0f .. 1.45f)
        val normalized = ((rmsDb + 52f) / 52f).coerceIn(0.0f, 1.0f)
        targetScale = 1.0f + normalized * 0.45f
        invalidate()
    }

    fun startBreathing() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                breathingPhase = va.animatedValue as Float
                rotationAngle = (rotationAngle + 0.024f) % (2 * Math.PI.toFloat())

                // Smooth audio scale decay
                val idlePulse = 1.0f + 0.06f * sin(breathingPhase)
                val target = maxOf(targetScale, idlePulse)
                currentScale = currentScale * 0.70f + target * 0.30f
                targetScale = targetScale * 0.88f

                invalidate()
            }
            start()
        }
    }

    fun stopBreathing() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(cx, cy) * 0.72f
        if (baseRadius <= 0) return

        val density = resources.displayMetrics.density

        // 1. Subtle, crisp neon quantum halo (NOT diluted or washed out)
        val haloRadius = baseRadius * (0.86f + 0.05f * sin(breathingPhase)) * currentScale
        auraPaint.strokeWidth = 1.6f * density
        auraPaint.color = Color.argb(100, Color.red(primaryGlow), Color.green(primaryGlow), Color.blue(primaryGlow))
        canvas.drawCircle(cx, cy, haloRadius, auraPaint)

        // 2. 3D Isometric Projection with 4D Hypercube depth
        val yaw = rotationAngle
        val pitch = 0.36f // 20-degree elevation tilt for clear 3D perspective
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)

        val outerSize = baseRadius * 0.54f * currentScale
        val innerSize = outerSize * (0.48f + 0.06f * sin(breathingPhase))

        val outer2D = Array(8) { floatArrayOf(0f, 0f, 0f) } // x, y, z
        val inner2D = Array(8) { floatArrayOf(0f, 0f, 0f) }

        val d = 3.6f

        for (i in 0 until 8) {
            val normal = cubeNormals[i]

            // Outer vertex 3D
            val ox0 = normal[0] * outerSize
            val oy0 = normal[1] * outerSize
            val oz0 = normal[2] * outerSize

            val ox1 = ox0 * cosYaw - oz0 * sinYaw
            val oz1 = ox0 * sinYaw + oz0 * cosYaw
            val oy1 = oy0 * cosPitch - oz1 * sinPitch
            val oz2 = oy0 * sinPitch + oz1 * cosPitch

            val pOuter = d / (d - oz2 / (baseRadius * 2.5f))
            outer2D[i][0] = cx + ox1 * pOuter
            outer2D[i][1] = cy + oy1 * pOuter
            outer2D[i][2] = oz2

            // Inner vertex 3D
            val ix0 = normal[0] * innerSize
            val iy0 = normal[1] * innerSize
            val iz0 = normal[2] * innerSize

            val ix1 = ix0 * cosYaw - iz0 * sinYaw
            val iz1 = ix0 * sinYaw + iz0 * cosYaw
            val iy1 = iy0 * cosPitch - iz1 * sinPitch
            val iz2 = iy0 * sinPitch + iz1 * cosPitch

            val pInner = d / (d - iz2 / (baseRadius * 2.5f))
            inner2D[i][0] = cx + ix1 * pInner
            inner2D[i][1] = cy + iy1 * pInner
            inner2D[i][2] = iz2
        }

        // 3. Draw Connecting Hypercube Struts (Outer corner -> Inner corner)
        // Clean laser beams, subtle so they do not clutter the cube
        strutPaint.strokeWidth = 1.2f * density
        strutPaint.color = Color.argb(120, Color.red(secondaryGlow), Color.green(secondaryGlow), Color.blue(secondaryGlow))
        for (i in 0 until 8) {
            canvas.drawLine(outer2D[i][0], outer2D[i][1], inner2D[i][0], inner2D[i][1], strutPaint)
        }

        // 4. Draw Inner Cube Edges with Realistic Depth (Front vs Rear)
        for (edge in cubeEdges) {
            val pA = inner2D[edge.first]
            val pB = inner2D[edge.second]
            val zAvg = (pA[2] + pB[2]) / 2f
            if (zAvg >= 0) {
                // Front edge: bright, sharp violet
                innerEdgePaint.strokeWidth = 2.2f * density
                innerEdgePaint.color = Color.argb(255, Color.red(secondaryGlow), Color.green(secondaryGlow), Color.blue(secondaryGlow))
            } else {
                // Back edge: dimmed, subtle violet
                innerEdgePaint.strokeWidth = 1.4f * density
                innerEdgePaint.color = Color.argb(85, Color.red(secondaryGlow), Color.green(secondaryGlow), Color.blue(secondaryGlow))
            }
            canvas.drawLine(pA[0], pA[1], pB[0], pB[1], innerEdgePaint)
        }

        // 5. Draw Outer Cube Edges with Realistic Depth (Front vs Rear)
        for (edge in cubeEdges) {
            val pA = outer2D[edge.first]
            val pB = outer2D[edge.second]
            val zAvg = (pA[2] + pB[2]) / 2f
            if (zAvg >= 0) {
                // Front edge: crisp cyber cyan
                outerEdgePaint.strokeWidth = 2.8f * density
                outerEdgePaint.color = Color.argb(255, Color.red(primaryGlow), Color.green(primaryGlow), Color.blue(primaryGlow))
            } else {
                // Back edge: dimmed cyber cyan
                outerEdgePaint.strokeWidth = 1.6f * density
                outerEdgePaint.color = Color.argb(90, Color.red(primaryGlow), Color.green(primaryGlow), Color.blue(primaryGlow))
            }
            canvas.drawLine(pA[0], pA[1], pB[0], pB[1], outerEdgePaint)
        }

        // 6. Draw Crisp Crystalline Vertex Nodes (Front vertices get highlights)
        for (i in 0 until 8) {
            val oz = outer2D[i][2]
            if (oz >= -outerSize * 0.2f) {
                // Front-facing outer node
                vertexPaint.color = primaryGlow
                canvas.drawCircle(outer2D[i][0], outer2D[i][1], 3.5f * density, vertexPaint)
                vertexPaint.color = Color.WHITE
                canvas.drawCircle(outer2D[i][0], outer2D[i][1], 1.5f * density, vertexPaint)
            }

            val iz = inner2D[i][2]
            if (iz >= 0) {
                vertexPaint.color = secondaryGlow
                canvas.drawCircle(inner2D[i][0], inner2D[i][1], 2.4f * density, vertexPaint)
            }
        }

        // 7. Central Radiant Quantum Singularity
        val coreRadius = (4.0f * density * currentScale).coerceIn(3.0f * density, 6.5f * density)
        vertexPaint.color = primaryGlow
        canvas.drawCircle(cx, cy, coreRadius, vertexPaint)
        vertexPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, coreRadius * 0.5f, vertexPaint)
    }
}
