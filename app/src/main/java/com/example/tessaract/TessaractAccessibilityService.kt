package com.example.tessaract

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Project T.E.S.S.A.R.A.C.T. - System Accessibility Service
 * Provides automated screen inspection, hierarchy node extraction,
 * and direct system screenshot capabilities.
 */
class TessaractAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TessaractA11y"
        var isServiceConnected: Boolean = false
            private set
        private var instance: TessaractAccessibilityService? = null

        @Volatile
        var currentForegroundPackage: String? = null
            private set

        data class AutoSendTask(
            val targetPackage: String,
            val messageText: String,
            val timestamp: Long = System.currentTimeMillis(),
            val callback: ((Boolean, String) -> Unit)? = null
        )

        @Volatile
        private var pendingAutoSendTask: AutoSendTask? = null
        private val handler = Handler(Looper.getMainLooper())

        /**
         * Arms the accessibility service to automatically locate and click the Send button
         * once the target messaging app (WhatsApp / SMS Messages) is brought to the foreground.
         */
        fun armAutoSend(targetPackage: String, messageText: String, onComplete: ((Boolean, String) -> Unit)? = null) {
            val task = AutoSendTask(
                targetPackage = targetPackage.lowercase(),
                messageText = messageText,
                callback = onComplete
            )
            pendingAutoSendTask = task
            Log.i(TAG, "Armed auto-send task for package '$targetPackage' with message '$messageText'")

            // Auto-expire task after 14 seconds if app was never opened
            handler.postDelayed({
                if (pendingAutoSendTask == task) {
                    Log.w(TAG, "Auto-send task expired for $targetPackage")
                    pendingAutoSendTask = null
                    onComplete?.invoke(false, "Timeout waiting for send button in $targetPackage")
                }
            }, 14000L)

            // If target app is already in the foreground, trigger check immediately
            instance?.let { service ->
                if (service.matchesTargetPackage(currentForegroundPackage, targetPackage)) {
                    service.scheduleSendButtonClick(task)
                }
            }
        }

        fun disarmAutoSend() {
            pendingAutoSendTask = null
        }

        /**
         * Privacy Shield: Excludes sensitive packages (System settings, Banking, UPI, Wallets, Keyguard)
         * from screen inspection, scraping, and overlay rendering.
         */
        fun isSensitiveApp(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val pkg = packageName.lowercase()

            // 1. System Settings & Security
            if (pkg == "com.android.settings" || pkg.contains("settings") || pkg.contains("securitycenter")) {
                return true
            }

            // 2. Lock screen / Keyguard / SystemUI Lock
            if (pkg.contains("keyguard") || pkg.contains("lockscreen")) {
                return true
            }

            // 3. Known Payment / Banking / UPI Packages
            val knownPaymentPackages = setOf(
                "com.google.android.apps.nbu.paisa.user", // Google Pay (Tez)
                "com.google.android.apps.walletnfcrel",  // Google Wallet
                "com.phonepe.app",                       // PhonePe
                "net.one97.paytm",                       // Paytm
                "in.org.npci.upiapp",                    // BHIM UPI
                "com.paypal.android.p2pmobile",          // PayPal
                "com.squareup.cash",                     // Cash App
                "com.venmo",                             // Venmo
                "com.dreamplug.androidapp",              // CRED
                "com.sbi.upi",                           // SBI YONO / UPI
                "com.csam.icici.bank.imobile",           // ICICI iMobile
                "com.msf.kbank.mobile",                  // Kotak Mobile
                "com.snapwork.hdfc",                     // HDFC Mobile
                "com.axis.mobile",                       // Axis Mobile
                "io.metamask",                           // MetaMask
                "com.binance.dev",                       // Binance
                "com.coinbase.android"                   // Coinbase
            )
            if (knownPaymentPackages.contains(pkg)) return true

            // 4. Financial & Credential Keywords
            val sensitiveKeywords = listOf(
                "bank", "banking", "wallet", "paisa", "phonepe", "paytm",
                "paypal", "cred", "passwords", "authenticator", "1password",
                "bitwarden", "lastpass", "keepass"
            )
            return sensitiveKeywords.any { pkg.contains(it) }
        }

        fun isKeyguardActive(context: Context): Boolean {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            return km?.isKeyguardLocked == true
        }

        fun canTakeScreenshotDirectly(): Boolean {
            return isServiceConnected && instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }

        fun captureScreenshot(
            onSuccess: (Bitmap) -> Unit,
            onError: (String) -> Unit
        ) {
            val service = instance
            if (service == null || !isServiceConnected) {
                onError("Accessibility Service is not connected")
                return
            }

            if (isSensitiveApp(currentForegroundPackage) || isKeyguardActive(service)) {
                onError("Privacy Protection Active: Screen capture blocked on sensitive interfaces (settings/financial/lockscreen)")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                try {
                                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                    if (bitmap != null) {
                                        val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        buffer.close()
                                        onSuccess(softBitmap)
                                    } else {
                                        buffer.close()
                                        onError("Null hardware bitmap returned")
                                    }
                                } catch (e: Exception) {
                                    buffer.close()
                                    onError("Failed copying hardware buffer: ${e.message}")
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                val reason = when (errorCode) {
                                    ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal system error"
                                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Interval too short"
                                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
                                    else -> "Error code $errorCode"
                                }
                                onError("Screenshot failed: $reason")
                            }
                        }
                    )
                } catch (e: Exception) {
                    onError("Exception taking screenshot: ${e.message}")
                }
            } else {
                onError("Direct takeScreenshot requires Android 11 (API 30)+")
            }
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            if (isServiceConnected && instance != null) return true
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedServiceName = "${context.packageName}/${TessaractAccessibilityService::class.java.canonicalName}"
            return enabledServices.contains(expectedServiceName) || enabledServices.contains(TessaractAccessibilityService::class.java.simpleName)
        }

        fun scrapeActiveScreenText(): String {
            val service = instance ?: return "[]"

            // Privacy Exclusion: Never scrape sensitive screens (Settings, Banking, UPI, Keyguard)
            if (isSensitiveApp(currentForegroundPackage) || isKeyguardActive(service)) {
                Log.w(TAG, "Suppressed text scraping on sensitive package: $currentForegroundPackage")
                val blockedObj = JSONObject().apply {
                    put("error", "SENSITIVE_APP_BLOCKED")
                    put("reason", "Privacy Protection Active: Screen scraping blocked on sensitive financial or settings interface")
                    put("packageName", currentForegroundPackage ?: "unknown")
                }
                return JSONArray().put(blockedObj).toString()
            }

            val array = JSONArray()
            val seenTexts = mutableSetOf<String>()

            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val viewId = node.viewIdResourceName ?: ""

                val meaningfulText = when {
                    !text.isNullOrBlank() -> text
                    !desc.isNullOrBlank() -> desc
                    else -> null
                }

                if (meaningfulText != null && !seenTexts.contains(meaningfulText)) {
                    seenTexts.add(meaningfulText)
                    val obj = JSONObject()
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    obj.put("text", meaningfulText)
                    obj.put("contentDescription", desc ?: "")
                    obj.put("viewId", viewId)
                    obj.put("isClickable", node.isClickable)
                    obj.put("bounds", "[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                    array.put(obj)
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        child.recycle()
                    }
                }
            }

            // 1. Check root in active window
            val root = service.rootInActiveWindow
            if (root != null) {
                try {
                    traverse(root)
                } catch (e: Exception) {
                    Log.w(TAG, "Error traversing active window: ${e.message}")
                } finally {
                    root.recycle()
                }
            }

            // 2. Also check interactive windows if root was empty or overlay intercepted
            if (array.length() == 0) {
                try {
                    val windows = service.windows
                    if (windows != null) {
                        for (win in windows) {
                            val winRoot = win.root
                            if (winRoot != null) {
                                try {
                                    traverse(winRoot)
                                } finally {
                                    winRoot.recycle()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error traversing windows list: ${e.message}")
                }
            }

            return array.toString()
        }

        fun saveBitmapToFile(context: Context, bitmap: Bitmap): String {
            val dir = context.getExternalFilesDir("screenshots") ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "tessaract_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            return file.absolutePath
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        Log.i(TAG, "T.E.S.S.A.R.A.C.T. Accessibility Service Connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        val pkg = event.packageName?.toString()

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.isNullOrBlank()) {
            currentForegroundPackage = pkg
            if (isSensitiveApp(pkg)) {
                Log.i(TAG, "Sensitive package foregrounded: $pkg - actively hiding overlay")
                TessaractOverlayService.hide(this)
            }
        }

        // Check if pending auto-send task is waiting for this package
        val task = pendingAutoSendTask
        if (task != null && (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            val currentPkg = pkg ?: currentForegroundPackage
            if (matchesTargetPackage(currentPkg, task.targetPackage)) {
                scheduleSendButtonClick(task)
            }
        }
    }

    private fun matchesTargetPackage(current: String?, target: String): Boolean {
        if (current.isNullOrBlank()) return false
        val c = current.lowercase()
        val t = target.lowercase()
        return when {
            c == t -> true
            t.contains("whatsapp") && (c.contains("whatsapp") || c.contains("com.whatsapp")) -> true
            t.contains("messag") && (c.contains("messaging") || c.contains("mms") || c.contains("sms")) -> true
            else -> c.contains(t) || t.contains(c)
        }
    }

    private var isClickAttemptScheduled = false

    private fun scheduleSendButtonClick(task: AutoSendTask) {
        if (isClickAttemptScheduled) return
        isClickAttemptScheduled = true

        // WhatsApp / Messages typically takes ~600-900ms to mount conversation view and enable send button
        handler.postDelayed({
            isClickAttemptScheduled = false
            if (pendingAutoSendTask != task) return@postDelayed
            val clicked = tryClickSendButton(task)
            if (clicked) {
                Log.i(TAG, "Successfully triggered Send button click via accessibility!")
                pendingAutoSendTask = null
                task.callback?.invoke(true, "Message sent automatically via ${task.targetPackage}")
            } else {
                // Retry once after 700ms in case UI was still animating
                handler.postDelayed({
                    if (pendingAutoSendTask == task) {
                        val retryClicked = tryClickSendButton(task)
                        if (retryClicked) {
                            Log.i(TAG, "Successfully triggered Send button click on retry!")
                            pendingAutoSendTask = null
                            task.callback?.invoke(true, "Message sent automatically")
                        }
                    }
                }, 700L)
            }
        }, 750L)
    }

    private fun tryClickSendButton(task: AutoSendTask): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClickSendNode(root, task.targetPackage)
        } finally {
            root.recycle()
        }
    }

    private fun findAndClickSendNode(root: AccessibilityNodeInfo, targetPackage: String): Boolean {
        // 1. Check known specific resource IDs
        val targetIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.google.android.apps.messaging:id/send_message_button",
            "com.samsung.android.messaging:id/send_button",
            "com.android.mms:id/send_button"
        )
        for (id in targetIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (performClickOnNodeOrParent(node)) {
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }

        // 2. Traversal: look for buttons with contentDescription or text matching "Send" or send icon
        return searchForSendNode(root)
    }

    private fun searchForSendNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isSendDescriptor = desc == "send" || desc == "send message" || desc == "send sms" ||
                desc == "enviar" || desc == "envoyer" || desc.startsWith("send ") ||
                text == "send" || viewId.endsWith(":id/send") || viewId.endsWith("/send")

        if (isSendDescriptor && (node.isClickable || node.parent?.isClickable == true)) {
            if (performClickOnNodeOrParent(node)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = searchForSendNode(child)
                child.recycle()
                if (found) return true
            }
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val parent = node.parent
        if (parent != null) {
            val clicked = if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                false
            }
            parent.recycle()
            return clicked
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "T.E.S.S.A.R.A.C.T. Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
        if (instance == this) instance = null
        Log.i(TAG, "T.E.S.S.A.R.A.C.T. Accessibility Service Destroyed")
    }
}
