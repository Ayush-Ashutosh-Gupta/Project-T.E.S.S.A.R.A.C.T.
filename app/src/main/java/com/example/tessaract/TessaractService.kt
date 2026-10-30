package com.example.tessaract

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Project T.E.S.S.A.R.A.C.T. - Persistent Background Hardware Daemon
 * Features:
 * - Two distinct modes:
 *     1. STANDBY: Zero overlay HUD, ultra low power & CPU, duty-cycled AudioRecord keyword monitor.
 *     2. ACTIVE_LISTENING: Overlay HUD shown with live voice waves, continuous multi-command listening
 *        until the user explicitly says "stop listening" or taps the standby button.
 * - Dynamic acoustic decibel reactive speech recognition with high-accuracy parameters.
 * - Screen Capture via Accessibility & MediaProjection fallback.
 * - Resilient to task removal from Recent Apps (stopWithTask=false + AlarmManager resurrection).
 * - Thermal & battery guardian with adaptive duty-cycle throttling.
 */
class TessaractService : Service() {

    enum class DaemonMode {
        STANDBY,
        ACTIVE_LISTENING
    }

    companion object {
        const val TAG = "TessaractService"
        const val CHANNEL_ID = "tessaract_daemon_channel"
        const val NOTIFICATION_ID = 4096

        const val ACTION_START_SERVICE = "com.example.tessaract.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.tessaract.STOP_SERVICE"
        const val ACTION_TRIGGER_VOICE_PROMPT = "com.example.tessaract.TRIGGER_VOICE_PROMPT"
        const val ACTION_GO_TO_STANDBY = "com.example.tessaract.GO_TO_STANDBY"
        const val ACTION_SIMULATE_TRIGGER = "com.example.tessaract.SIMULATE_TRIGGER"
        const val ACTION_ENABLE_PROJECTION = "com.example.tessaract.ENABLE_PROJECTION"
        const val ACTION_DISABLE_PROJECTION = "com.example.tessaract.DISABLE_PROJECTION"
        const val ACTION_WAKE_BROADCAST = "com.example.tessaract.WAKE_BROADCAST"
        const val ACTION_SELECT_DISAMBIGUATION_INDEX = "com.example.tessaract.SELECT_DISAMBIGUATION_INDEX"
        const val EXTRA_TRIGGER_PHRASE = "extra_trigger_phrase"
        const val EXTRA_DISAMBIGUATION_INDEX = "extra_disambiguation_index"

        @Volatile var isRunning = false
        @Volatile var currentMode: DaemonMode = DaemonMode.STANDBY
        @Volatile var isThrottled = false
        @Volatile var currentBatteryLevel = 100
        @Volatile var currentThermalStatus = "NORMAL"
        @Volatile var listeningLoopCycleCount = 0L
        @Volatile var isProjectionActive = false
        var instance: TessaractService? = null

        var statusCallback: ((String, Map<String, Any>) -> Unit)? = null

        fun enableProjectionMode(context: Context) {
            isProjectionActive = true
            instance?.updateForegroundServiceState("Visual Frame Capture Pipeline Active")
            val intent = Intent(context, TessaractService::class.java).apply {
                action = ACTION_ENABLE_PROJECTION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed enabling projection foreground type: ${e.message}")
            }
        }

        fun disableProjectionMode(context: Context) {
            val intent = Intent(context, TessaractService::class.java).apply {
                action = ACTION_DISABLE_PROJECTION
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed disabling projection foreground type: ${e.message}")
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var listeningJob: Job? = null
    private val isLoopActive = AtomicBoolean(false)

    private val normalIntervalMs = 80L
    private val throttledIntervalMs = 300L
    private var currentIntervalMs = normalIntervalMs

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private lateinit var calibrationEngine: AcousticCalibrationEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private val isSpeechRecognizing = AtomicBoolean(false)
    private var lastStatusText = "Acoustic Standby - Say 'Hey Tesseract'"
    private var consecutiveSilencesInActiveMode = 0
    private var pendingContactCandidates: List<LocalComponentRouter.ContactCandidate>? = null

    @Volatile
    private var lastHeardTranscript: String = ""
    private var lastWakeVerificationAttemptTime: Long = 0L
    private var lastRmsTelemetryTime: Long = 0L

    // Hard timeout watchdog for SpeechRecognizer during ACTIVE_LISTENING mode
    private val speechTimeoutRunnable = Runnable {
        if (isSpeechRecognizing.get() && currentMode == DaemonMode.ACTIVE_LISTENING) {
            val transcript = lastHeardTranscript.trim()
            Log.w(TAG, "SpeechRecognizer watchdog timeout reached in active listening. Candidate transcript: '$transcript'")
            isSpeechRecognizing.set(false)
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling recognizer: ${e.message}")
            }
            if (transcript.isNotBlank()) {
                Log.i(TAG, "Watchdog executing captured transcript: '$transcript'")
                lastHeardTranscript = ""
                consecutiveSilencesInActiveMode = 0
                TessaractOverlayService.updateText(this@TessaractService, "\"$transcript\"", "Executing command...")
                updateNotificationText("Executing: \"$transcript\"")
                executeVoiceRoute(transcript)
            } else {
                consecutiveSilencesInActiveMode++
                if (consecutiveSilencesInActiveMode >= 4) {
                    transitionToStandby("Session timeout - Entering standby")
                } else {
                    scheduleActiveListeningRestart(400L)
                }
            }
        }
    }

    // Fast natural silence commit runnable: executes ~900ms after user finishes speaking
    private val silenceCommitRunnable = Runnable {
        if (isSpeechRecognizing.get() && currentMode == DaemonMode.ACTIVE_LISTENING) {
            val transcript = lastHeardTranscript.trim()
            if (transcript.isNotBlank()) {
                Log.i(TAG, "Silence pause detected -> executing captured command immediately: '$transcript'")
                isSpeechRecognizing.set(false)
                lastHeardTranscript = ""
                mainHandler.removeCallbacks(speechTimeoutRunnable)
                try {
                    speechRecognizer?.cancel()
                } catch (e: Exception) {}

                if (LocalComponentRouter.isStandbyCommand(transcript)) {
                    transitionToStandby("User command: $transcript")
                } else {
                    TessaractOverlayService.updateText(this@TessaractService, "\"$transcript\"", "Executing command...")
                    updateNotificationText("Executing: \"$transcript\"")
                    executeVoiceRoute(transcript)
                }
            }
        }
    }

    // Standby session refresh watchdog: smoothly refreshes idle recognition every ~12s
    private val standbyWatchdogRunnable = Runnable {
        if (isSpeechRecognizing.get() && currentMode == DaemonMode.STANDBY) {
            Log.d(TAG, "Standby session refresh watchdog triggered")
            isSpeechRecognizing.set(false)
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {}
            scheduleStandbyRestart(300L)
        }
    }

    // Single controlled runnable for restarting standby listening engine
    private val standbyRestartRunnable = Runnable {
        if (currentMode == DaemonMode.STANDBY && !isSpeechRecognizing.get()) {
            startStandbyListeningEngine()
        }
    }

    private fun scheduleStandbyRestart(delayMs: Long) {
        mainHandler.removeCallbacks(standbyRestartRunnable)
        if (currentMode == DaemonMode.STANDBY) {
            mainHandler.postDelayed(standbyRestartRunnable, delayMs)
        }
    }

    // Single controlled runnable for restarting active command listening
    private val activeListeningRestartRunnable = Runnable {
        if (currentMode == DaemonMode.ACTIVE_LISTENING && !isSpeechRecognizing.get()) {
            continueActiveCommandListening()
        }
    }

    private fun scheduleActiveListeningRestart(delayMs: Long) {
        mainHandler.removeCallbacks(activeListeningRestartRunnable)
        if (currentMode == DaemonMode.ACTIVE_LISTENING) {
            mainHandler.postDelayed(activeListeningRestartRunnable, delayMs)
        }
    }

    // Battery Monitor Receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100) / scale
                    currentBatteryLevel = batteryPct
                    evaluateSelfProtection("Battery update: $batteryPct%")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Initializing T.E.S.S.A.R.A.C.T. Persistent Foreground Daemon")
        createNotificationChannel()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        calibrationEngine = AcousticCalibrationEngine(this)
        registerProtectionListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_STOP_SERVICE -> {
                transitionToStandby()
                stopListeningEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                isProjectionActive = false
                notifyTelemetry("onServiceStateChanged", mapOf("isRunning" to false, "mode" to "STOPPED"))
                return START_NOT_STICKY
            }
            ACTION_ENABLE_PROJECTION -> {
                isProjectionActive = true
                isRunning = true
                updateForegroundServiceState("Visual Frame Capture Pipeline Active")
                return START_STICKY
            }
            ACTION_DISABLE_PROJECTION -> {
                isProjectionActive = false
                if (isRunning) {
                    updateForegroundServiceState(
                        if (currentMode == DaemonMode.ACTIVE_LISTENING) "Listening for commands..."
                        else "Acoustic Standby - Say 'Hey Tesseract'"
                    )
                }
                return START_STICKY
            }
            ACTION_TRIGGER_VOICE_PROMPT -> {
                Log.i(TAG, "Manual voice prompt requested via intent")
                transitionToActiveListening("Voice trigger activated")
            }
            ACTION_GO_TO_STANDBY -> {
                Log.i(TAG, "Standby requested via intent")
                transitionToStandby("Standby command received")
            }
            ACTION_SIMULATE_TRIGGER -> {
                val phrase = intent?.getStringExtra(EXTRA_TRIGGER_PHRASE) ?: "Hey Tesseract"
                dispatchWakeInterrupt(phrase)
            }
            ACTION_SELECT_DISAMBIGUATION_INDEX -> {
                val index = intent?.getIntExtra(EXTRA_DISAMBIGUATION_INDEX, -1) ?: -1
                val candidate = pendingContactCandidates?.getOrNull(index)
                if (candidate != null) {
                    pendingContactCandidates = null
                    TessaractOverlayService.clearDisambiguation(this)
                    LocalComponentRouter.executePhoneCall(this, candidate.phoneNumber)
                    calibrationEngine.speak("Calling ${candidate.displayName}")
                    TessaractOverlayService.updateText(this, "Calling", "${candidate.displayName}\n${candidate.phoneNumber}")
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 2500L)
                }
            }
            else -> {
                isRunning = true
                currentMode = DaemonMode.STANDBY
                // Make sure overlay is NOT showing in standby!
                TessaractOverlayService.hide(this)
                updateForegroundServiceState("Acoustic Standby - Say 'Hey Tesseract'")
                startStandbyListeningEngine()
                notifyTelemetry("onServiceStateChanged", mapOf("isRunning" to true, "mode" to "STANDBY", "throttled" to isThrottled))
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "TESSARACT task removed from recent apps. Ensuring persistent service continuity.")

        val restartServiceIntent = Intent(applicationContext, TessaractService::class.java).apply {
            action = ACTION_START_SERVICE
            setPackage(packageName)
        }
        val restartPendingIntent = PendingIntent.getService(
            applicationContext,
            101,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        try {
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not set task removal restart alarm: ${e.message}")
        }

        if (!isLoopActive.get() && !isSpeechRecognizing.get()) {
            if (currentMode == DaemonMode.ACTIVE_LISTENING) {
                continueActiveCommandListening()
            } else {
                startStandbyListeningEngine()
            }
        }
    }

    private fun updateForegroundServiceState(statusMessage: String) {
        lastStatusText = statusMessage
        val notification = buildForegroundNotification(statusMessage)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (isProjectionActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting foreground with type: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed basic startForeground fallback: ${e2.message}")
            }
        }
    }

    fun updateNotificationText(statusMessage: String) {
        lastStatusText = statusMessage
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(statusMessage))
    }

    // AudioRecord hardware sampling state
    private var audioRecord: AudioRecord? = null
    @Volatile var currentRmsDbfs: Float = -60f
    private val sampleRate = 16000
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        sampleRate / 4
    )

    fun pauseForCalibration() {
        mainHandler.post {
            isSpeechRecognizing.set(false)
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {}
            stopListeningEngine()
        }
    }

    fun resumeAfterCalibration() {
        mainHandler.post {
            if (currentMode == DaemonMode.STANDBY) {
                startStandbyListeningEngine()
            } else if (currentMode == DaemonMode.ACTIVE_LISTENING) {
                continueActiveCommandListening()
            }
        }
    }

    /**
     * Continuous hardware listening loop for STANDBY MODE.
     * Maintains an open, low-latency SpeechRecognizer listening pipeline so that the exact moment
     * the user utters the wake phrase (or custom command), the audio is captured in real time
     * instead of being dropped by delayed start-up.
     * The glassmorphic HUD overlay remains completely hidden during standby.
     */
    fun startStandbyListeningEngine() {
        mainHandler.post {
            mainHandler.removeCallbacks(standbyRestartRunnable)
            mainHandler.removeCallbacks(standbyWatchdogRunnable)

            if (currentMode != DaemonMode.STANDBY) return@post
            if (isSpeechRecognizing.getAndSet(true)) return@post

            stopListeningEngine() // release any raw audioRecord handle

            val hasMicPermission = ContextCompat.checkSelfPermission(
                this@TessaractService,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                Log.w(TAG, "Microphone permission not granted yet; waiting in standby")
                isSpeechRecognizing.set(false)
                runDutyCycledAcousticRadar()
                return@post
            }

            Log.d(TAG, "Starting standby wake word listening session (HUD hidden)")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Prefer local on-device speech model for zero latency and total privacy
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra("android.speech.extra.PREFER_OFFLINE", true)
                // Relaxed silence detection for natural wake phrase capture without rapid timeouts
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@TessaractService)
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {}
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Standby recognizer ready (listening for wake word)")
                        mainHandler.removeCallbacks(standbyWatchdogRunnable)
                        mainHandler.postDelayed(standbyWatchdogRunnable, 12000L)
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech detected in standby")
                        mainHandler.removeCallbacks(standbyWatchdogRunnable)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        currentRmsDbfs = rmsdB
                        listeningLoopCycleCount++
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRmsTelemetryTime >= 300L) {
                            lastRmsTelemetryTime = now
                            notifyTelemetry("onAcousticRmsSample", mapOf(
                                "rmsDb" to currentRmsDbfs,
                                "cycle" to listeningLoopCycleCount,
                                "battery" to currentBatteryLevel,
                                "thermal" to currentThermalStatus,
                                "throttled" to isThrottled,
                                "mode" to currentMode.name
                            ))
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        mainHandler.removeCallbacks(standbyWatchdogRunnable)
                        isSpeechRecognizing.set(false)
                        Log.d(TAG, "Standby listening session ended with code $error")
                        if (currentMode != DaemonMode.STANDBY) return

                        try {
                            speechRecognizer?.cancel()
                        } catch (e: Exception) {}

                        // Clean up and recreate instance only if client or server binder fails
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = null
                            } catch (e: Exception) {}
                        }

                        // Smoothly renew standby listening session with calm backoff
                        val restartDelay = when {
                            isThrottled -> throttledIntervalMs
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> 1200L
                            else -> 300L
                        }
                        scheduleStandbyRestart(restartDelay)
                    }

                    override fun onResults(results: Bundle?) {
                        mainHandler.removeCallbacks(standbyWatchdogRunnable)
                        isSpeechRecognizing.set(false)
                        if (currentMode != DaemonMode.STANDBY) return

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                        Log.d(TAG, "Standby recognized candidate matches: $matches")
                        val topMatch = matches.firstOrNull() ?: ""
                        if (topMatch.isNotBlank()) {
                            notifyTelemetry("onSpeechPartial", mapOf("text" to topMatch))
                        }

                        var matchedWake: Pair<String, String>? = null
                        for (heard in matches) {
                            val extract = CustomVoiceConfig.extractWakeAndCommand(this@TessaractService, heard)
                            if (extract != null) {
                                matchedWake = extract
                                break
                            } else if (LocalComponentRouter.isWakePhrase(this@TessaractService, heard)) {
                                matchedWake = Pair(heard, "")
                                break
                            }
                        }

                        if (matchedWake != null) {
                            val wakeName = matchedWake.first
                            val cmd = matchedWake.second
                            Log.i(TAG, "WAKE WORD MATCHED: '$wakeName' (command: '$cmd') -> AWAKENING!")
                            mainHandler.removeCallbacks(standbyRestartRunnable)
                            currentMode = DaemonMode.ACTIVE_LISTENING
                            try {
                                speechRecognizer?.setRecognitionListener(null)
                                speechRecognizer?.cancel()
                            } catch (e: Exception) {}
                            notifyTelemetry("onSpeechFinal", mapOf("text" to wakeName))
                            transitionToActiveListening("Wake phrase recognized: \"$wakeName\"", initialCommand = cmd)
                        } else {
                            // Non-wake utterance in standby: seamlessly loop back into standby listening with calm delay
                            scheduleStandbyRestart(if (isThrottled) throttledIntervalMs else 300L)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (currentMode != DaemonMode.STANDBY) return
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                        for (partialText in partials) {
                            if (partialText.isNotBlank()) {
                                notifyTelemetry("onSpeechPartial", mapOf("text" to partialText))
                                val extract = CustomVoiceConfig.extractWakeAndCommand(this@TessaractService, partialText)
                                if (extract != null || LocalComponentRouter.isWakePhrase(this@TessaractService, partialText)) {
                                    val wakeName = extract?.first ?: partialText
                                    val cmd = extract?.second ?: ""
                                    Log.i(TAG, "WAKE WORD DETECTED ON PARTIAL: '$wakeName' -> AWAKENING INSTANTLY!")
                                    mainHandler.removeCallbacks(standbyWatchdogRunnable)
                                    mainHandler.removeCallbacks(standbyRestartRunnable)
                                    currentMode = DaemonMode.ACTIVE_LISTENING
                                    isSpeechRecognizing.set(false)
                                    try {
                                        speechRecognizer?.setRecognitionListener(null)
                                        speechRecognizer?.cancel()
                                    } catch (e: Exception) {}
                                    notifyTelemetry("onSpeechFinal", mapOf("text" to wakeName))
                                    transitionToActiveListening("Wake phrase recognized: \"$wakeName\"", initialCommand = cmd)
                                    return
                                }
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
                mainHandler.removeCallbacks(standbyWatchdogRunnable)
                mainHandler.postDelayed(standbyWatchdogRunnable, 12000L)

            } catch (e: Exception) {
                Log.w(TAG, "Failed starting standby speech recognizer: ${e.message}")
                mainHandler.removeCallbacks(standbyWatchdogRunnable)
                isSpeechRecognizing.set(false)
                try {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                } catch (ex: Exception) {}
                scheduleStandbyRestart(1500L)
            }
        }
    }

    private fun runDutyCycledAcousticRadar() {
        if (isLoopActive.getAndSet(true)) return
        listeningJob?.cancel()
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isLoopActive.get() && currentMode == DaemonMode.STANDBY) {
                listeningLoopCycleCount++
                delay(if (isThrottled) throttledIntervalMs else normalIntervalMs)
            }
        }
    }

    fun stopListeningEngine() {
        isLoopActive.set(false)
        listeningJob?.cancel()
        listeningJob = null
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
        Log.i(TAG, "Hardware acoustic monitoring loop halted and audio resources released")
    }

    /**
     * Awakens T.E.S.S.A.R.A.C.T. into ACTIVE_LISTENING mode.
     * Shows HUD overlay with live voice wave, vibrates, and enters continuous command listening.
     */
    fun transitionToActiveListening(reason: String = "", initialCommand: String = "") {
        mainHandler.post {
            currentMode = DaemonMode.ACTIVE_LISTENING
            mainHandler.removeCallbacks(standbyWatchdogRunnable)
            mainHandler.removeCallbacks(standbyRestartRunnable)
            mainHandler.removeCallbacks(speechTimeoutRunnable)
            mainHandler.removeCallbacks(silenceCommitRunnable)
            isSpeechRecognizing.set(false)
            consecutiveSilencesInActiveMode = 0

            try {
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.cancel()
            } catch (e: Exception) {}

            // Haptic feedback
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(80)
                }
            } catch (e: Exception) {}

            // Turn ON HUD Overlay
            TessaractOverlayService.show(this@TessaractService, "Listening for commands...")
            updateForegroundServiceState("T.E.S.S.A.R.A.C.T. Active - Listening...")

            notifyTelemetry("onWakeTriggerReceived", mapOf(
                "triggerPhrase" to reason,
                "timestamp" to System.currentTimeMillis(),
                "mode" to "ACTIVE_LISTENING"
            ))

            if (initialCommand.isNotBlank()) {
                Log.i(TAG, "Executing initial command from wake utterance immediately: '$initialCommand'")
                TessaractOverlayService.updateText(this@TessaractService, "\"$initialCommand\"", "Executing command...")
                updateNotificationText("Executing: \"$initialCommand\"")
                executeVoiceRoute(initialCommand)
            } else {
                calibrationEngine.speak("Yes? Listening.") {
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 250L)
                }
            }
        }
    }

    /**
     * Puts T.E.S.S.A.R.A.C.T. into STANDBY mode.
     * Hides HUD overlay, acknowledges with TTS, and enters ultra-low-power keyword listening.
     */
    fun transitionToStandby(reason: String = "Going to standby") {
        mainHandler.post {
            currentMode = DaemonMode.STANDBY
            mainHandler.removeCallbacks(speechTimeoutRunnable)
            mainHandler.removeCallbacks(silenceCommitRunnable)
            mainHandler.removeCallbacks(standbyWatchdogRunnable)
            mainHandler.removeCallbacks(standbyRestartRunnable)
            mainHandler.removeCallbacks(activeListeningRestartRunnable)
            isSpeechRecognizing.set(false)

            try {
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.cancel()
            } catch (e: Exception) {}

            // HIDE HUD Overlay
            TessaractOverlayService.hide(this@TessaractService)
            updateForegroundServiceState("Acoustic Standby - Say 'Hey Tesseract'")

            notifyTelemetry("onServiceStateChanged", mapOf(
                "mode" to "STANDBY",
                "reason" to reason
            ))

            calibrationEngine.speak("Standing by.") {
                scheduleStandbyRestart(300L)
            }
        }
    }

    /**
     * Continuous command recognition loop while in ACTIVE_LISTENING mode.
     * Does NOT stop listening after one command — continues listening until user says "stop listening"!
     */
    fun continueActiveCommandListening() {
        mainHandler.post {
            mainHandler.removeCallbacks(activeListeningRestartRunnable)

            if (currentMode != DaemonMode.ACTIVE_LISTENING) return@post
            if (isSpeechRecognizing.getAndSet(true)) return@post

            stopListeningEngine()

            // Arm 5.0-second watchdog to allow complete, natural command speech
            mainHandler.removeCallbacks(speechTimeoutRunnable)
            mainHandler.removeCallbacks(silenceCommitRunnable)
            mainHandler.postDelayed(speechTimeoutRunnable, 5000L)

            // Update overlay & notification
            TessaractOverlayService.updateText(this@TessaractService, "T.E.S.S.A.R.A.C.T.", "Listening for commands...")
            updateNotificationText("Listening: speak your command")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Prefer local on-device speech models
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra("android.speech.extra.PREFER_OFFLINE", true)
                // Natural silence detection: 1.8s silence triggers completion
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {}
                }
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer ready for speech in active session")
                        TessaractOverlayService.updateText(this@TessaractService, "T.E.S.S.A.R.A.C.T.", "Listening...")
                        updateNotificationText("Listening for command...")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer speech detected")
                        consecutiveSilencesInActiveMode = 0
                        lastHeardTranscript = ""
                        mainHandler.removeCallbacks(speechTimeoutRunnable)
                        mainHandler.removeCallbacks(silenceCommitRunnable)
                        mainHandler.postDelayed(speechTimeoutRunnable, 4500L)
                        TessaractOverlayService.updateText(this@TessaractService, "Hearing speech...", "Capturing command...")
                        updateNotificationText("Hearing speech...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        currentRmsDbfs = rmsdB
                        TessaractOverlayService.updateRms(this@TessaractService, rmsdB)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRmsTelemetryTime >= 300L) {
                            lastRmsTelemetryTime = now
                            notifyTelemetry("onAcousticRmsSample", mapOf(
                                "rmsDb" to currentRmsDbfs,
                                "battery" to currentBatteryLevel,
                                "thermal" to currentThermalStatus,
                                "mode" to currentMode.name
                            ))
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer end of speech")
                        TessaractOverlayService.updateText(this@TessaractService, "Processing...", "Executing command...")
                        updateNotificationText("Processing command...")

                        // Fast commit on end of speech (under 250ms) so user never waits infinitely
                        val pending = lastHeardTranscript.trim()
                        if (pending.isNotBlank()) {
                            mainHandler.removeCallbacks(silenceCommitRunnable)
                            mainHandler.postDelayed(silenceCommitRunnable, 200L)
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error in active mode: $error")
                        mainHandler.removeCallbacks(speechTimeoutRunnable)
                        mainHandler.removeCallbacks(silenceCommitRunnable)
                        isSpeechRecognizing.set(false)

                        // If we heard speech before the error (e.g. no match / timeout after speaking), route it!
                        val fallback = lastHeardTranscript.trim()
                        if (fallback.isNotBlank()) {
                            Log.i(TAG, "Routing buffered speech following error $error: '$fallback'")
                            lastHeardTranscript = ""
                            consecutiveSilencesInActiveMode = 0
                            TessaractOverlayService.updateText(this@TessaractService, "\"$fallback\"", "Executing command...")
                            updateNotificationText("Executing: \"$fallback\"")
                            executeVoiceRoute(fallback)
                            return
                        }

                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = null
                            } catch (e: Exception) {}
                        }

                        if (currentMode == DaemonMode.ACTIVE_LISTENING) {
                            consecutiveSilencesInActiveMode++
                            if (consecutiveSilencesInActiveMode >= 4) {
                                // User walked away after repeated silences -> auto-standby
                                transitionToStandby("Session idle")
                            } else {
                                val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 400L
                                scheduleActiveListeningRestart(delayMs)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        mainHandler.removeCallbacks(speechTimeoutRunnable)
                        mainHandler.removeCallbacks(silenceCommitRunnable)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        var text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isBlank() && lastHeardTranscript.isNotBlank()) {
                            text = lastHeardTranscript.trim()
                        }
                        lastHeardTranscript = ""
                        Log.i(TAG, "Active speech recognition transcript: '$text'")
                        isSpeechRecognizing.set(false)

                        if (text.isNotEmpty()) {
                            consecutiveSilencesInActiveMode = 0
                            notifyTelemetry("onSpeechFinal", mapOf("text" to text))

                            // Check if user wants to STOP LISTENING / GO TO SLEEP
                            if (LocalComponentRouter.isStandbyCommand(text)) {
                                Log.i(TAG, "Standby command recognized: '$text'")
                                transitionToStandby("User command: $text")
                                return
                            }

                            // Execute normal command and KEEP LISTENING!
                            TessaractOverlayService.updateText(this@TessaractService, "\"$text\"", "Executing command...")
                            updateNotificationText("Executing: \"$text\"")
                            executeVoiceRoute(text)
                        } else {
                            consecutiveSilencesInActiveMode++
                            if (consecutiveSilencesInActiveMode >= 4) {
                                transitionToStandby("No speech detected")
                            } else {
                                scheduleActiveListeningRestart(350L)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partialText = partials?.firstOrNull()?.trim() ?: ""
                        if (partialText.isNotEmpty()) {
                            lastHeardTranscript = partialText
                            notifyTelemetry("onSpeechPartial", mapOf("text" to partialText))
                            TessaractOverlayService.updateText(this@TessaractService, "\"$partialText\"", "Listening...")
                            updateNotificationText("Hearing: \"$partialText\"")
                            // Fast commit ~900ms after user pauses speaking
                            mainHandler.removeCallbacks(silenceCommitRunnable)
                            mainHandler.postDelayed(silenceCommitRunnable, 900L)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting active speech recognition: ${e.message}")
                mainHandler.removeCallbacks(speechTimeoutRunnable)
                mainHandler.removeCallbacks(silenceCommitRunnable)
                isSpeechRecognizing.set(false)
                scheduleActiveListeningRestart(1000L)
            }
        }
    }

    /**
     * Executes the parsed voice route offline, speaks TTS response,
     * updates the overlay, and IMMEDIATELY CONTINUES LISTENING FOR THE NEXT COMMAND!
     */
    private fun executeVoiceRoute(text: String) {
        val routeResult = LocalComponentRouter.processVoiceInput(this, text, pendingContactCandidates)
        notifyTelemetry("onCommandRouted", mapOf("text" to text, "route" to routeResult.javaClass.simpleName))

        when (routeResult) {
            is LocalComponentRouter.RouteResult.Standby -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                transitionToStandby(routeResult.message)
                return
            }

            is LocalComponentRouter.RouteResult.Routine -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Routine: ${routeResult.title}", routeResult.logSummary)
                updateNotificationText("Routine: ${routeResult.title}")
                calibrationEngine.speak(routeResult.spokenAnnouncement) {
                    if (routeResult.enterStandby) {
                        transitionToStandby("Routine requested standby")
                    } else {
                        mainHandler.postDelayed({
                            continueActiveCommandListening()
                        }, 1200L)
                    }
                }
            }

            is LocalComponentRouter.RouteResult.CircleToSearch -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Circle to Search", "Circle any area on screen to search & scrape")
                calibrationEngine.speak("Circle any area on your screen.") {
                    TessaractCircleToSearchActivity.launch(this)
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 1500L)
                }
            }

            is LocalComponentRouter.RouteResult.ScreenCapture -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Capturing Screen...", "Executing snapshot")
                updateNotificationText("Capturing display frame...")
                if (TessaractAccessibilityService.canTakeScreenshotDirectly()) {
                    TessaractAccessibilityService.captureScreenshot(
                        onSuccess = { bmp ->
                            val path = TessaractAccessibilityService.saveBitmapToFile(this, bmp)
                            calibrationEngine.speak("Screenshot captured and saved.")
                            TessaractOverlayService.updateText(this, "Screenshot Saved", File(path).name)
                            updateNotificationText("Screenshot saved: ${File(path).name}")
                            mainHandler.postDelayed({
                                continueActiveCommandListening()
                            }, 2000L)
                        },
                        onError = { err ->
                            Log.w(TAG, "Accessibility capture fallback: $err")
                            requestMediaProjectionScreenshot()
                        }
                    )
                } else {
                    requestMediaProjectionScreenshot()
                }
            }

            is LocalComponentRouter.RouteResult.ScreenScrape -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                if (TessaractAccessibilityService.isServiceConnected) {
                    try {
                        val json = TessaractAccessibilityService.scrapeActiveScreenText()
                        val array = JSONArray(json)
                        val textItems = mutableListOf<String>()
                        for (i in 0 until minOf(array.length(), 6)) {
                            val t = array.getJSONObject(i).optString("text")
                            if (t.isNotBlank()) textItems.add(t)
                        }
                        val summary = if (textItems.isNotEmpty()) {
                            "Screen contains: " + textItems.joinToString(", ")
                        } else {
                            "Screen analyzed: ${array.length()} UI elements visible."
                        }
                        calibrationEngine.speak(summary)
                        TessaractOverlayService.updateText(this, "Screen Scraped", "${array.length()} elements found: " + textItems.take(2).joinToString("; "))
                    } catch (e: Exception) {
                        calibrationEngine.speak("Failed reading screen text.")
                    }
                } else {
                    val prompt = "Accessibility screen access is not enabled. Opening Settings now. Please turn ON T.E.S.S.A.R.A.C.T."
                    calibrationEngine.speak(prompt)
                    TessaractOverlayService.updateText(this, "Screen Access Needed", "Opening Settings...")
                    try {
                        val a11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(a11yIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot launch a11y settings: ${e.message}")
                    }
                }
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 3000L)
            }

            is LocalComponentRouter.RouteResult.Flashlight -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                calibrationEngine.speak(routeResult.message)
                TessaractOverlayService.updateText(this, "Flashlight", routeResult.message)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2000L)
            }

            is LocalComponentRouter.RouteResult.DirectCall -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                val prompt = "Calling ${routeResult.name}"
                calibrationEngine.speak(prompt)
                TessaractOverlayService.updateText(this, "Calling", "${routeResult.name}\n${routeResult.number}")
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2500L)
            }

            is LocalComponentRouter.RouteResult.DisambiguateContacts -> {
                pendingContactCandidates = routeResult.candidates
                val candidateList = ArrayList(routeResult.candidates)
                val speakText = if (routeResult.candidates.size >= 2) {
                    "Found multiple contacts for ${routeResult.query}. Say 1 for ${routeResult.candidates[0].displayName}, or 2 for ${routeResult.candidates[1].displayName}, or tap below."
                } else {
                    "Found contacts matching ${routeResult.query}. Say 1 or tap below."
                }
                calibrationEngine.speak(speakText)
                TessaractOverlayService.showDisambiguation(
                    this,
                    "Multiple Contacts",
                    "Found ${routeResult.candidates.size} matches for '${routeResult.query}'. Say 1 or 2, or tap below:",
                    candidateList
                )
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 3000L)
            }

            is LocalComponentRouter.RouteResult.AppLaunch -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                val prompt = "Launching ${routeResult.appName}"
                calibrationEngine.speak(prompt)
                TessaractOverlayService.updateText(this, "Opening App", routeResult.appName)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2500L)
            }

            is LocalComponentRouter.RouteResult.AppStoreFallback -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                val prompt = "${routeResult.appName} not installed. Opening store."
                calibrationEngine.speak(prompt)
                TessaractOverlayService.updateText(this, "Store Fallback", routeResult.appName)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2500L)
            }

            is LocalComponentRouter.RouteResult.BrowserSearch -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                val prompt = "Searching for ${routeResult.query}"
                calibrationEngine.speak(prompt)
                TessaractOverlayService.updateText(this, "Search", routeResult.query)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2500L)
            }

            is LocalComponentRouter.RouteResult.MessagingDispatch -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                val prompt = "Sending message to ${routeResult.recipient}"
                calibrationEngine.speak(prompt)
                TessaractOverlayService.updateText(this, prompt, routeResult.message)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2500L)
            }

            is LocalComponentRouter.RouteResult.MultiStepResult -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Multi-Step Execution", routeResult.summary)
                updateNotificationText("Multi-step: ${routeResult.summary}")
                calibrationEngine.speak(routeResult.summary) {
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 1200L)
                }
            }

            is LocalComponentRouter.RouteResult.MediaAction -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Media Action", routeResult.message)
                updateNotificationText(routeResult.message)
                calibrationEngine.speak(routeResult.message) {
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 1200L)
                }
            }

            is LocalComponentRouter.RouteResult.ConnectivityAction -> {
                pendingContactCandidates = null
                TessaractOverlayService.clearDisambiguation(this)
                TessaractOverlayService.updateText(this, "Device Control", routeResult.message)
                updateNotificationText(routeResult.message)
                calibrationEngine.speak(routeResult.message) {
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 1200L)
                }
            }

            is LocalComponentRouter.RouteResult.Handled -> {
                if (pendingContactCandidates != null && (routeResult.message.contains("cancel", ignoreCase = true) || routeResult.message.contains("standing by", ignoreCase = true))) {
                    pendingContactCandidates = null
                    TessaractOverlayService.clearDisambiguation(this)
                }
                calibrationEngine.speak(routeResult.message)
                TessaractOverlayService.updateText(this, "T.E.S.S.A.R.A.C.T.", routeResult.message)
                mainHandler.postDelayed({
                    continueActiveCommandListening()
                }, 2000L)
            }

            is LocalComponentRouter.RouteResult.Unrecognized -> {
                val rawInput = routeResult.rawInput

                // Step 1: Check Tavily Search BYOK first for factual/simple web answers
                val isTavilyReady = TavilySearchClient.isSearchEnabled(this) && TavilySearchClient.isConfigured(this)
                val isNemotronReady = NebiusNemotronClient.isAiEnabled(this) && NebiusNemotronClient.isConfigured(this)

                if (isTavilyReady) {
                    Log.i(TAG, "Routing query to Tavily Search BYOK first: '$rawInput'")
                    TessaractOverlayService.updateText(this, "Tavily Search", "Searching web...")
                    updateNotificationText("Tavily searching: \"$rawInput\"")

                    serviceScope.launch {
                        val searchResult = TavilySearchClient.search(this@TessaractService, rawInput)
                        val tavilyAnswer = searchResult.getOrNull()?.let { TavilySearchClient.extractSpokenAnswer(it) }

                        if (searchResult.isSuccess && !tavilyAnswer.isNullOrBlank() && !tavilyAnswer.contains("no direct answer was found", ignoreCase = true)) {
                            withContext(Dispatchers.Main) {
                                Log.i(TAG, "Tavily direct answer: '$tavilyAnswer'")
                                TessaractOverlayService.updateText(this@TessaractService, "Tavily Answer", tavilyAnswer)
                                updateNotificationText("Tavily: $tavilyAnswer")
                                notifyTelemetry("onTavilyResponse", mapOf("query" to rawInput, "reply" to tavilyAnswer))
                                calibrationEngine.speak(tavilyAnswer) {
                                    mainHandler.postDelayed({
                                        continueActiveCommandListening()
                                    }, 1000L)
                                }
                            }
                        } else {
                            // Tavily failed or returned no direct answer -> Fall back to Nvidia Nemotron on Nebius Cloud
                            Log.w(TAG, "Tavily returned no direct answer. Falling back to Nvidia Nemotron on Nebius Cloud.")
                            if (isNemotronReady) {
                                queryNemotronFallback(rawInput)
                            } else {
                                withContext(Dispatchers.Main) {
                                    val fallbackMsg = tavilyAnswer ?: "No answer found. Connect your Nebius Nemotron key in Settings for general reasoning."
                                    TessaractOverlayService.updateText(this@TessaractService, "Tavily Search", fallbackMsg)
                                    calibrationEngine.speak(fallbackMsg) {
                                        mainHandler.postDelayed({
                                            continueActiveCommandListening()
                                        }, 1500L)
                                    }
                                }
                            }
                        }
                    }
                } else if (isNemotronReady) {
                    // Tavily not configured, go directly to Nvidia Nemotron on Nebius Cloud
                    queryNemotronFallback(rawInput)
                } else {
                    val prompt = "Command not recognized: \"$rawInput\". You can connect your Tavily or Nvidia Nemotron API key in Settings to answer questions."
                    calibrationEngine.speak(prompt)
                    TessaractOverlayService.updateText(this, "Unrecognized", rawInput)
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 2500L)
                }
            }
        }
    }

    private fun queryNemotronFallback(rawInput: String) {
        Log.i(TAG, "Routing general voice query to Nvidia Nemotron on Nebius Cloud: '$rawInput'")
        TessaractOverlayService.updateText(this, "Nvidia Nemotron", "Querying Nebius Cloud...")
        updateNotificationText("Nemotron thinking: \"$rawInput\"")

        serviceScope.launch {
            val result = NebiusNemotronClient.queryNemotron(this@TessaractService, rawInput)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val reply = result.getOrNull() ?: "Done."
                    Log.i(TAG, "Nemotron reply: '$reply'")
                    TessaractOverlayService.updateText(this@TessaractService, "Nemotron AI", reply)
                    updateNotificationText("Nemotron: $reply")
                    notifyTelemetry("onNemotronResponse", mapOf("query" to rawInput, "reply" to reply))
                    calibrationEngine.speak(reply) {
                        mainHandler.postDelayed({
                            continueActiveCommandListening()
                        }, 1000L)
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Inference error"
                    Log.w(TAG, "Nemotron query error: $err")
                    val spokenErr = if (err.contains("401")) {
                        "Nemotron authentication failed. Please verify your Nebius API Key in Settings."
                    } else {
                        "Nemotron response error: ${err.take(80)}"
                    }
                    TessaractOverlayService.updateText(this@TessaractService, "Nemotron Alert", err)
                    updateNotificationText(err)
                    calibrationEngine.speak(spokenErr) {
                        mainHandler.postDelayed({
                            continueActiveCommandListening()
                        }, 2000L)
                    }
                }
            }
        }
    }

    private fun requestMediaProjectionScreenshot() {
        enableProjectionMode(this)
        TessaractMediaProjectionActivity.requestScreenCapture(
            this,
            object : TessaractMediaProjectionActivity.FrameCaptureCallback {
                override fun onSuccess(jpegBytes: ByteArray) {
                    val dir = getExternalFilesDir("screenshots") ?: filesDir
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "tessaract_mp_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(jpegBytes)
                    calibrationEngine.speak("Screenshot captured and saved to storage.")
                    TessaractOverlayService.updateText(this@TessaractService, "Screenshot Saved", file.name)
                    updateNotificationText("Screenshot saved: ${file.name}")
                    disableProjectionMode(this@TessaractService)
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 2000L)
                }

                override fun onError(errorCode: String, description: String) {
                    val err = "Screen capture failed: $description"
                    calibrationEngine.speak(err)
                    TessaractOverlayService.updateText(this@TessaractService, "Capture Failed", errorCode)
                    updateNotificationText(err)
                    disableProjectionMode(this@TessaractService)
                    mainHandler.postDelayed({
                        continueActiveCommandListening()
                    }, 2000L)
                }
            }
        )
    }

    fun dispatchWakeInterrupt(phrase: String) {
        Log.i(TAG, "Acoustic trigger condition satisfied: '$phrase'. Dispatching interrupt.")

        val wakeIntent = Intent(ACTION_WAKE_BROADCAST).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRIGGER_PHRASE, phrase)
        }
        sendBroadcast(wakeIntent)

        transitionToActiveListening(phrase)
    }

    private fun registerProtectionListeners() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                currentThermalStatus = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN ($status)"
                }
                evaluateSelfProtection("Thermal update: $currentThermalStatus")
            }
            try {
                powerManager?.addThermalStatusListener(mainHandler::post, thermalListener!!)
            } catch (e: Exception) {
                Log.w(TAG, "Thermal status listener error: ${e.message}")
            }
        }
    }

    fun recheckGuardianPolicy() {
        evaluateSelfProtection("User Guardian settings updated")
    }

    private fun evaluateSelfProtection(triggerReason: String) {
        val guardianEnabled = GuardianConfig.isProtectionEnabled(this)
        val threshold = GuardianConfig.getBatteryThreshold(this)

        val shouldThrottle = guardianEnabled && (
            (currentBatteryLevel in 1..threshold) ||
            currentThermalStatus in listOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        )

        if (shouldThrottle != isThrottled) {
            isThrottled = shouldThrottle
            currentIntervalMs = if (isThrottled) throttledIntervalMs else normalIntervalMs
            Log.w(TAG, "Guardian throttle transitioned to $isThrottled due to: $triggerReason (Threshold: $threshold%, Enabled: $guardianEnabled). Interval: ${currentIntervalMs}ms")
            notifyTelemetry("onThermalProtectionAlert", mapOf(
                "throttled" to isThrottled,
                "batteryLevel" to currentBatteryLevel,
                "thermalStatus" to currentThermalStatus,
                "reason" to triggerReason
            ))
        }
    }

    private fun notifyTelemetry(event: String, payload: Map<String, Any>) {
        mainHandler.post {
            statusCallback?.invoke(event, payload)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "T.E.S.S.A.R.A.C.T. Core Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors acoustic environment and executes local offline commands"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusMessage: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action button: If in standby, "Wake Tesseract". If in active, "Go to Sleep".
        val isStandby = currentMode == DaemonMode.STANDBY
        val actionIntent = Intent(this, TessaractService::class.java).apply {
            action = if (isStandby) ACTION_TRIGGER_VOICE_PROMPT else ACTION_GO_TO_STANDBY
        }
        val actionPendingIntent = PendingIntent.getService(
            this,
            1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionLabel = if (isStandby) "Wake Up / Speak" else "Stop Listening (Sleep)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Project T.E.S.S.A.R.A.C.T.")
            .setContentText(statusMessage)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, actionLabel, actionPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusMessage))
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.i(TAG, "Destroying TessaractService and releasing native resources")
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                powerManager?.removeThermalStatusListener(thermalListener!!)
            } catch (e: Exception) {}
        }

        stopListeningEngine()
        mainHandler.removeCallbacks(speechTimeoutRunnable)
        mainHandler.removeCallbacks(silenceCommitRunnable)
        mainHandler.removeCallbacks(standbyWatchdogRunnable)
        mainHandler.removeCallbacks(standbyRestartRunnable)
        mainHandler.removeCallbacks(activeListeningRestartRunnable)
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {}
        TessaractOverlayService.hide(this)
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
