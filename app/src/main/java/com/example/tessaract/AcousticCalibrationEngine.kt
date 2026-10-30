package com.example.tessaract

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Real Acoustic Calibration and Dynamic Noise Threshold Engine.
 * Samples live PCM audio from the device microphone to establish authentic
 * acoustic noise floor and dynamic voice surge detection thresholds.
 * Zero demo data or hardcoded anchors.
 */
class AcousticCalibrationEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AcousticCalibration"
        private const val PREFS_NAME = "tessaract_acoustic_calibration"
        private const val KEY_NOISE_FLOOR = "calibrated_noise_floor_db"
        private const val KEY_SPECTRAL_CLARITY = "calibrated_spectral_clarity"
        private const val KEY_ANCHOR_COUNT = "calibrated_anchor_count"
        private const val KEY_WAKE_THRESHOLD = "calibrated_wake_threshold_db"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingUtteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    var noiseFloorDb: Float
        get() = prefs.getFloat(KEY_NOISE_FLOOR, -55.0f)
        set(value) = prefs.edit().putFloat(KEY_NOISE_FLOOR, value).apply()

    var spectralClarity: Float
        get() = prefs.getFloat(KEY_SPECTRAL_CLARITY, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_SPECTRAL_CLARITY, value).apply()

    var anchorCount: Int
        get() = prefs.getInt(KEY_ANCHOR_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_ANCHOR_COUNT, value).apply()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS Language US not supported")
            } else {
                isTtsInitialized = true
                Log.i(TAG, "TTS initialized successfully")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id ->
                            pendingUtteranceCallbacks.remove(id)?.let { cb ->
                                mainHandler.post(cb)
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { id ->
                            pendingUtteranceCallbacks.remove(id)?.let { cb ->
                                mainHandler.post(cb)
                            }
                        }
                    }
                })
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    fun isSpeaking(): Boolean = isTtsInitialized && tts?.isSpeaking == true

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (e: Exception) {}
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.let { mainHandler.post(it) }
            return
        }
        if (!isTtsInitialized || tts == null) {
            onDone?.let { mainHandler.post(it) }
            return
        }
        mainHandler.post {
            val utteranceId = "TessaractTts_${System.currentTimeMillis()}_${(100..999).random()}"
            if (onDone != null) {
                pendingUtteranceCallbacks[utteranceId] = onDone
                mainHandler.postDelayed({
                    pendingUtteranceCallbacks.remove(utteranceId)?.invoke()
                }, 8000L)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun updateTone(pitch: Float, rate: Float) {
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    fun calculateDynamicConfidenceThreshold(): Float {
        if (anchorCount == 0) return 0.70f
        val baseMargin = 0.65f
        val clarityBonus = (spectralClarity * 0.20f).coerceIn(0.0f, 0.20f)
        val anchorBonus = (anchorCount * 0.03f).coerceIn(0.0f, 0.12f)
        return (baseMargin + clarityBonus + anchorBonus).coerceIn(0.60f, 0.95f)
    }

    fun getWakeThresholdDb(): Float {
        val stored = prefs.getFloat(KEY_WAKE_THRESHOLD, Float.NaN)
        if (!stored.isNaN()) return stored.coerceIn(-48.0f, -30.0f)
        // Dynamic margin: 12 dB above measured noise floor, clamped to human conversational range (-48 to -30 dBFS)
        return (noiseFloorDb + 12.0f).coerceIn(-48.0f, -30.0f)
    }

    fun resetCalibration() {
        prefs.edit().clear().apply()
        noiseFloorDb = -55.0f
        spectralClarity = 0.85f
        anchorCount = 0
        prefs.edit().putFloat(KEY_WAKE_THRESHOLD, -42.0f).apply()
    }

    data class LiveCalibrationResult(
        val noiseFloorDb: Float,
        val spectralClarity: Float,
        val dynamicConfidenceThreshold: Float,
        val wakeThresholdDb: Float
    )

    /**
     * Conducts a real microphone sampling session over the specified duration.
     * Computes actual RMS noise floor, zero-crossing spectral density,
     * and sets dynamic voice surge thresholds.
     */
    suspend fun performLiveAcousticCalibration(
        durationMs: Long = 2500L,
        onProgress: (currentDb: Float, progress: Float) -> Unit
    ): LiveCalibrationResult = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate / 2)

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            throw SecurityException("Microphone permission RECORD_AUDIO is not granted.")
        }

        var recorder: AudioRecord? = null
        val sampledDbList = mutableListOf<Float>()
        var totalZeroCrossings = 0L
        var totalSamplesCounted = 0L

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            recorder.startRecording()

            val pcmChunk = ShortArray(1024)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < durationMs) {
                val read = recorder.read(pcmChunk, 0, pcmChunk.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = pcmChunk[i]
                        sum += (sample * sample).toDouble()
                        if (i > 0 && ((sample >= 0 && pcmChunk[i - 1] < 0) || (sample < 0 && pcmChunk[i - 1] >= 0))) {
                            totalZeroCrossings++
                        }
                    }
                    totalSamplesCounted += read
                    val rms = sqrt(sum / read)
                    val db = if (rms > 0.0001) (20.0 * log10(rms / 32767.0)).toFloat() else -90f
                    sampledDbList.add(db)

                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    withContext(Dispatchers.Main) {
                        onProgress(db, progress)
                    }
                }
                kotlinx.coroutines.delay(40L)
            }
        } finally {
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up calibration AudioRecord: ${e.message}")
            }
        }

        if (sampledDbList.isEmpty()) {
            throw IllegalStateException("No acoustic samples were captured during calibration.")
        }

        // Compute 25th percentile as ambient noise floor to avoid surge bias
        val sortedDbs = sampledDbList.sorted()
        val p25Index = (sortedDbs.size * 0.25f).toInt().coerceIn(0, sortedDbs.size - 1)
        val measuredNoiseFloor = sortedDbs[p25Index].coerceIn(-80.0f, -20.0f)

        // Spectral clarity based on zero-crossing frequency normalization
        val zcrRate = if (totalSamplesCounted > 0) totalZeroCrossings.toFloat() / totalSamplesCounted.toFloat() else 0.1f
        val measuredClarity = (1.0f - (zcrRate * 2.5f).coerceIn(0.0f, 0.7f)).coerceIn(0.3f, 0.98f)

        // Save real anchor data
        val newAnchorCount = anchorCount + 1
        anchorCount = newAnchorCount
        noiseFloorDb = measuredNoiseFloor
        spectralClarity = measuredClarity

        val wakeThreshold = (measuredNoiseFloor + 12.0f).coerceIn(-48.0f, -30.0f)
        prefs.edit().putFloat(KEY_WAKE_THRESHOLD, wakeThreshold).apply()

        val dynConfidence = calculateDynamicConfidenceThreshold()

        Log.i(TAG, "Calibration finished: NoiseFloor=${measuredNoiseFloor}dB, Clarity=$measuredClarity, WakeThreshold=${wakeThreshold}dB, Confidence=$dynConfidence")

        LiveCalibrationResult(
            noiseFloorDb = measuredNoiseFloor,
            spectralClarity = measuredClarity,
            dynamicConfidenceThreshold = dynConfidence,
            wakeThresholdDb = wakeThreshold
        )
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        }
    }
}
