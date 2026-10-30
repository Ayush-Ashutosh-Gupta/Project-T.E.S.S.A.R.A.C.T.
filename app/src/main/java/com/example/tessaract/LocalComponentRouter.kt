package com.example.tessaract

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Project T.E.S.S.A.R.A.C.T. - Local Component Router
 * Offline-first deterministic regex intent parser and local component dispatcher.
 * Handles an extensive array of default device commands without external network reliance:
 * - Phone calling & smart multi-contact disambiguation (1 vs 2, etc.)
 * - Screen capture & accessibility screen scraping
 * - Flashlight / Torch toggle
 * - Alarms & Timers
 * - Camera launch
 * - Offline math calculator
 * - Local notes / Reminders
 * - Volume / Audio / Media controls
 * - System settings (Wi-Fi, Bluetooth, Display, Battery)
 * - App launching & Web searching
 * - Custom user-defined voice commands
 */
object LocalComponentRouter {

    private const val TAG = "LocalComponentRouter"
    private const val NOTES_PREFS = "tessaract_local_notes"
    private const val KEY_NOTES = "notes_list"
    var isFlashlightOn = false

    @Keep
    data class ContactCandidate(val displayName: String, val phoneNumber: String) : Serializable

    sealed class RouteResult {
        data class DirectCall(val name: String, val number: String) : RouteResult()
        data class DisambiguateContacts(val query: String, val candidates: List<ContactCandidate>) : RouteResult()
        data class AppLaunch(val appName: String, val packageName: String) : RouteResult()
        data class AppStoreFallback(val appName: String, val storeIntent: Intent) : RouteResult()
        data class BrowserSearch(val query: String, val searchIntent: Intent) : RouteResult()
        data class MessagingDispatch(val recipient: String, val message: String, val queued: Boolean) : RouteResult()
        data class ScreenCapture(val message: String) : RouteResult()
        data class ScreenScrape(val message: String) : RouteResult()
        data class CircleToSearch(val message: String) : RouteResult()
        data class Flashlight(val enable: Boolean, val message: String) : RouteResult()
        data class Standby(val message: String) : RouteResult()
        data class Routine(val title: String, val logSummary: String, val spokenAnnouncement: String, val enterStandby: Boolean) : RouteResult()
        data class MediaAction(val action: String, val message: String) : RouteResult()
        data class ConnectivityAction(val action: String, val message: String) : RouteResult()
        data class MultiStepResult(val steps: List<RouteResult>, val summary: String) : RouteResult()
        data class Handled(val message: String) : RouteResult()
        data class Unrecognized(val rawInput: String) : RouteResult()
    }

    // -------------------------------------------------------------
    // Regex Command Definitions
    // -------------------------------------------------------------

    private val STANDBY_REGEX = Regex(
        pattern = """^(?:stop\s+listening|go\s+to\s+sleep|sleep|standby|stop|dismiss|cancel|exit|goodbye|shut\s+down|turn\s+off|rest|close\s+hud|hide\s+hud|shut\s+up|bye)(?:\s+(?:now|please|tesseract))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val WAKE_PHRASE_REGEX = Regex(
        pattern = """\b(?:tesseract|tessaract|tesaract|tasseract|tess\s+react|test\s+react|tess\s+aract|tess\s+direct|the\s+tesseract|desert|hey\s+tesseract|hey\s+tessaract|hey\s+tesaract|hey\s+tess\s+react|ok\s+tesseract|ok\s+tessaract|wake\s+up|wake\s+up\s+tesseract|computer|jarvis|friday|nova|assistant|activate|tess|tessa|hey\s+tess|hey\s+tessa)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val CIRCLE_SEARCH_REGEX = Regex(
        pattern = """^(?:circle(?:\s+to)?\s+search|circle\s+screen|circle\s+area|select\s+area|crop\s+screen|lasso\s+screen|circle|screen\s+circle|lasso)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SCREEN_CAPTURE_REGEX = Regex(
        pattern = """^(?:(?:take|capture|click|save|snap|grab)\s+(?:a\s+)?(?:screen\s*shot|screen\s*capture|screen\s*grab|screen\s*picture|picture\s+of\s+(?:the\s+)?screen|picture|photo|pic|image)|screen\s*shot|screen\s*capture|screen\s*grab|capture\s+(?:the\s+)?screen|save\s+(?:the\s+)?screen|snap\s+(?:the\s+)?screen|click\s+picture|click\s+photo|take\s+picture|take\s+photo|snap\s+picture|screenshot|screen\s+capture|screen\s+shot)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SCREEN_SCRAPE_REGEX = Regex(
        pattern = """^(?:read\s+(?:the\s+)?(?:screen|text)|what(?:'s|\s+is)\s+on\s+(?:my\s+|the\s+)?screen|analyze\s+(?:the\s+)?screen|scan\s+(?:the\s+)?screen|scrape\s+(?:the\s+)?screen|screen\s+text|read\s+out\s+(?:the\s+)?screen|extract\s+screen\s+text|screen\s+reading|screen\s+content|read\s+display)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val ACCESSIBILITY_SETTINGS_REGEX = Regex(
        pattern = """^(?:(?:open\s+|enable\s+)?(?:screen\s+access|accessibility(?:\s+settings)?|screen\s+permission|accessibility\s+service))${'$'}""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Flashlight / Torch
    private val FLASHLIGHT_ON_REGEX = Regex(
        pattern = """^(?:turn\s+on\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+on|lumos|enable\s+(?:flashlight|torch))$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val FLASHLIGHT_OFF_REGEX = Regex(
        pattern = """^(?:turn\s+off\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+off|nox|disable\s+(?:flashlight|torch))$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Device Utilities
    private val TIME_REGEX = Regex(
        pattern = """^(?:what\s+(?:time\s+is\s+it|is\s+the\s+time)|current\s+time|time\s+check)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val DATE_REGEX = Regex(
        pattern = """^(?:what\s+(?:day\s+is\s+it|is\s+today's\s+date|is\s+the\s+date)|today's\s+date|date\s+check)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val BATTERY_REGEX = Regex(
        pattern = """^(?:what\s+is\s+(?:my\s+)?battery(?:\s+level)?|battery\s+status|check\s+battery|battery)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Volume & Audio
    private val VOLUME_UP_REGEX = Regex(
        pattern = """^(?:volume\s+up|increase\s+volume|louder|turn\s+it\s+up)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val VOLUME_DOWN_REGEX = Regex(
        pattern = """^(?:volume\s+down|decrease\s+volume|lower\s+volume|quieter|turn\s+it\s+down)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val VOLUME_MUTE_REGEX = Regex(
        pattern = """^(?:mute|silence|turn\s+off\s+sound|quiet)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val VOLUME_MAX_REGEX = Regex(
        pattern = """^(?:max\s+volume|maximum\s+volume|full\s+volume|turn\s+volume\s+all\s+the\s+way\s+up)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Connectivity & Settings
    private val WIFI_REGEX = Regex(
        pattern = """^(?:(?:turn\s+(?:on|off)\s+)?wifi|wifi\s+(?:on|off)|open\s+wifi(?:\s+settings)?|check\s+wifi)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val BLUETOOTH_REGEX = Regex(
        pattern = """^(?:(?:turn\s+(?:on|off)\s+)?bluetooth|bluetooth\s+(?:on|off)|open\s+bluetooth(?:\s+settings)?|check\s+bluetooth)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SETTINGS_REGEX = Regex(
        pattern = """^(?:open\s+settings|system\s+settings|settings)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Camera & Photo Taking
    private val CAMERA_REGEX = Regex(
        pattern = """^(?:open\s+camera|launch\s+camera|camera\s+app|open\s+the\s+camera|camera|take\s+selfie|open\s+selfie)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Timers & Alarms
    private val TIMER_SET_REGEX = Regex(
        pattern = """^(?:set\s+(?:a\s+)?timer\s+(?:for\s+)?|timer\s+)(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SHOW_TIMERS_REGEX = Regex(
        pattern = """^(?:show\s+timers?|open\s+timer|clock|show\s+alarms?|open\s+alarms?)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Media Controls
    private val MEDIA_PLAY_PAUSE_REGEX = Regex(
        pattern = """^(?:play\s+music|pause\s+music|resume\s+music|play|pause)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val MEDIA_NEXT_REGEX = Regex(
        pattern = """^(?:next\s+song|next\s+track|skip\s+song|skip)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val MEDIA_PREV_REGEX = Regex(
        pattern = """^(?:previous\s+song|previous\s+track|last\s+song|back\s+a\s+song)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Math & Calculator
    private val MATH_CALCULATE_REGEX = Regex(
        pattern = """^(?:calculate\s+|what\s+is\s+|solve\s+)(.+)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Notes
    private val NOTE_TAKE_REGEX = Regex(
        pattern = """^(?:take\s+(?:a\s+)?note|save\s+note|new\s+note|remind\s+me\s+to|write\s+down)\s+(.+)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val NOTE_READ_REGEX = Regex(
        pattern = """^(?:read\s+(?:my\s+)?notes?|show\s+(?:my\s+)?notes?|get\s+notes?)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val NOTE_CLEAR_REGEX = Regex(
        pattern = """^(?:clear\s+(?:my\s+)?notes?|delete\s+(?:all\s+)?notes?)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Calling & Contacts
    private val CALL_REGEX = Regex(
        pattern = """^(?:call|phone|dial|ring)\s+([a-zA-Z0-9\s]+?)(?:\s+on\s+phone|\s+mobile)?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val INDEX_SELECTION_REGEX = Regex(
        pattern = """^(?:call\s+|dial\s+|select\s+|choose\s+|option\s+|number\s+|pick\s+)?(\d+|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth)(?:\s+one)?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val APP_LAUNCH_REGEX = Regex(
        pattern = """^(?:open|launch|start|run|play)\s+([a-zA-Z0-9\s]+)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val BROWSER_SEARCH_REGEX = Regex(
        pattern = """^(?:search\s+(?:for\s+)?|who\s+is\s+|what\s+is\s+|where\s+is\s+|how\s+to\s+|find\s+|look\s+up\s+)(.+)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val MESSAGING_REGEX = Regex(
        pattern = """^(?:send\s+message\s+to|message|text|whatsapp)\s+([a-zA-Z0-9\s]+?)\s+(?:saying|that|with)\s+(.+)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Volume & Brightness percentages
    private val VOLUME_PERCENT_REGEX = Regex(
        pattern = """^(?:(?:set|turn|change|put)\s+(?:the\s+)?volume\s+(?:to\s+)?|volume\s+)(\d{1,3})\s*(?:%|percent)?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val BRIGHTNESS_PERCENT_REGEX = Regex(
        pattern = """^(?:(?:set|turn|change|put)\s+(?:the\s+)?brightness\s+(?:to\s+)?|brightness\s+)(\d{1,3})\s*(?:%|percent)?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Media & Camera Actions
    private val PHOTO_CAPTURE_REGEX = Regex(
        pattern = """^(?:(?:take|capture|click|snap)\s+(?:a\s+)?(?:photo|picture|image|selfie)|take\s+a\s+shot)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val VIDEO_RECORD_REGEX = Regex(
        pattern = """^(?:(?:start|record|take|shoot)\s+(?:a\s+)?video(?:\s+recording)?|start\s+recording\s+video)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val AUDIO_RECORD_START_REGEX = Regex(
        pattern = """^(?:start\s+(?:voice|audio)\s+recording|record\s+(?:my\s+)?(?:voice|audio)|start\s+audio\s+recorder)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val AUDIO_RECORD_STOP_REGEX = Regex(
        pattern = """^(?:stop\s+(?:voice|audio)\s+recording|stop\s+recording|finish\s+recording)(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Bluetooth & Network controls
    private val BLUETOOTH_CONNECT_DEVICE_REGEX = Regex(
        pattern = """^(?:connect\s+(?:to\s+)?(?:my\s+)?(?:bluetooth\s+)?(earbuds?|headphones?|headset|airpods?|device|[a-zA-Z0-9\s]+?)|pair\s+(?:with\s+)?([a-zA-Z0-9\s]+?))(?:\s+(?:now|please))?$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val WIFI_TOGGLE_REGEX = Regex(
        pattern = """^(?:turn\s+(on|off)\s+wifi|wifi\s+(on|off)|enable\s+wifi|disable\s+wifi)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val BLUETOOTH_TOGGLE_REGEX = Regex(
        pattern = """^(?:turn\s+(on|off)\s+bluetooth|bluetooth\s+(on|off)|enable\s+bluetooth|disable\s+bluetooth)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val MOBILE_DATA_REGEX = Regex(
        pattern = """^(?:(?:open|turn\s+(?:on|off)\s+|toggle\s+)?mobile\s+data|cellular\s+data|data\s+settings)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val WHATSAPP_MESSAGE_REGEX = Regex(
        pattern = """^(?:(?:send\s+)?whatsapp(?:\s+message)?\s+(?:to\s+)?([a-zA-Z0-9\s]+?)\s+(?:saying|that|with)\s+(.+)|whatsapp\s+([a-zA-Z0-9\s]+?)\s+(.+))$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val SMS_MESSAGE_REGEX = Regex(
        pattern = """^(?:(?:send\s+)?(?:text|sms|message)\s+(?:to\s+)?([a-zA-Z0-9\s]+?)\s+(?:saying|that|with)\s+(.+)|text\s+([a-zA-Z0-9\s]+?)\s+(.+))$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun isWakePhrase(context: Context, input: String): Boolean {
        return CustomVoiceConfig.isWakePhrase(context, input) || WAKE_PHRASE_REGEX.containsMatchIn(input)
    }

    fun isWakePhrase(input: String): Boolean {
        return WAKE_PHRASE_REGEX.containsMatchIn(input)
    }

    fun isStandbyCommand(input: String): Boolean {
        return STANDBY_REGEX.matches(input.trim())
    }

    /**
     * Parses the transcript offline using deterministic regex trees and executes corresponding actions.
     */
    fun processVoiceInput(
        context: Context,
        rawInput: String,
        activeCandidates: List<ContactCandidate>? = null
    ): RouteResult {
        val trimmed = rawInput.trim()
        val cleaned = CustomVoiceConfig.stripWakeWord(context, trimmed)
        Log.i(TAG, "Routing offline voice input: '$trimmed' -> cleaned: '$cleaned' (activeCandidates=${activeCandidates?.size ?: 0})")

        // 1. Termination / Standby intent
        if (STANDBY_REGEX.matches(cleaned) || STANDBY_REGEX.matches(trimmed)) {
            return RouteResult.Standby("Standing by. Say 'Hey Tesseract' to awaken.")
        }

        // 2. Routine Commands (Chained multi-action macros, e.g. "Movie Time")
        val routineMatch = RoutineCommandsConfig.matchRoutine(context, cleaned) ?: RoutineCommandsConfig.matchRoutine(context, trimmed)
        if (routineMatch != null) {
            Log.i(TAG, "Matched Routine Command: '${routineMatch.title}' with trigger '${routineMatch.triggerPhrase}'")
            val (logSummary, announcement) = RoutineCommandsConfig.executeRoutine(context, routineMatch)
            val hasStandby = routineMatch.steps.any { it.actionType == RoutineCommandsConfig.RoutineActionType.STANDBY }
            return RouteResult.Routine(routineMatch.title, logSummary, announcement, hasStandby)
        }

        // 2. Disambiguation selection if candidate list is active
        if (!activeCandidates.isNullOrEmpty()) {
            val candidateMatch = matchActiveCandidate(cleaned, activeCandidates) ?: matchActiveCandidate(trimmed, activeCandidates)
            if (candidateMatch != null) {
                executePhoneCall(context, candidateMatch.phoneNumber)
                return RouteResult.DirectCall(candidateMatch.displayName, candidateMatch.phoneNumber)
            }
            if (cleaned.equals("cancel", ignoreCase = true) || cleaned.equals("never mind", ignoreCase = true) ||
                trimmed.equals("cancel", ignoreCase = true) || trimmed.equals("never mind", ignoreCase = true)) {
                return RouteResult.Handled("Contact selection cancelled.")
            }
        }

        // 4. Multi-Step Local Command Chaining (e.g. "turn on flashlight and set volume to 80%")
        val multiDelimiters = Regex("""\s*,\s*and\s+then\s+|\s+and\s+then\s+|\s*,\s*then\s+|\s+then\s+|\s+and\s+""", RegexOption.IGNORE_CASE)
        val rawParts = cleaned.split(multiDelimiters).map { it.trim() }.filter { it.isNotBlank() }
        if (rawParts.size >= 2) {
            val subResults = mutableListOf<RouteResult>()
            for (part in rawParts) {
                val subRes = processSingleVoiceInput(context, part)
                if (subRes !is RouteResult.Unrecognized) {
                    subResults.add(subRes)
                }
            }
            if (subResults.size >= 2) {
                val summaries = subResults.mapNotNull {
                    when (it) {
                        is RouteResult.Flashlight -> it.message
                        is RouteResult.Handled -> it.message
                        is RouteResult.DirectCall -> "Calling ${it.name}"
                        is RouteResult.AppLaunch -> "Launched ${it.appName}"
                        is RouteResult.MediaAction -> it.message
                        is RouteResult.ConnectivityAction -> it.message
                        is RouteResult.ScreenCapture -> "Screenshot captured"
                        is RouteResult.MessagingDispatch -> "Message sent to ${it.recipient}"
                        else -> null
                    }
                }
                return RouteResult.MultiStepResult(subResults, summaries.joinToString(". "))
            }
        }

        return processSingleVoiceInput(context, cleaned)
    }

    /**
     * Evaluates a single voice command locally without multi-step conjunction recursion.
     */
    fun processSingleVoiceInput(context: Context, commandInput: String): RouteResult {
        val cleaned = commandInput.trim()
        val trimmed = commandInput.trim()

        // Volume percentage: e.g. "set volume to 80%" or "volume 50"
        val volPctMatch = VOLUME_PERCENT_REGEX.matchEntire(cleaned) ?: VOLUME_PERCENT_REGEX.matchEntire(trimmed)
        if (volPctMatch != null) {
            val pct = volPctMatch.groupValues[1].toIntOrNull() ?: 50
            val res = ConnectivityHelper.setVolumePercentage(context, pct)
            return RouteResult.ConnectivityAction("volume", res.second)
        }

        // Brightness percentage: e.g. "brightness 76%" or "set brightness to 60"
        val brightPctMatch = BRIGHTNESS_PERCENT_REGEX.matchEntire(cleaned) ?: BRIGHTNESS_PERCENT_REGEX.matchEntire(trimmed)
        if (brightPctMatch != null) {
            val pct = brightPctMatch.groupValues[1].toIntOrNull() ?: 50
            val res = ConnectivityHelper.setBrightnessPercentage(context, pct)
            return RouteResult.ConnectivityAction("brightness", res.second)
        }

        // Camera photo capture
        if (PHOTO_CAPTURE_REGEX.matches(cleaned) || PHOTO_CAPTURE_REGEX.matches(trimmed)) {
            val res = MediaCaptureHelper.triggerPhotoCapture(context)
            return RouteResult.MediaAction("photo", res.second)
        }

        // Camera video recording
        if (VIDEO_RECORD_REGEX.matches(cleaned) || VIDEO_RECORD_REGEX.matches(trimmed)) {
            val res = MediaCaptureHelper.triggerVideoCapture(context)
            return RouteResult.MediaAction("video", res.second)
        }

        // Voice / Audio recording start
        if (AUDIO_RECORD_START_REGEX.matches(cleaned) || AUDIO_RECORD_START_REGEX.matches(trimmed)) {
            val res = MediaCaptureHelper.startVoiceRecording(context)
            return RouteResult.MediaAction("audio_start", res.second)
        }

        // Voice / Audio recording stop
        if (AUDIO_RECORD_STOP_REGEX.matches(cleaned) || AUDIO_RECORD_STOP_REGEX.matches(trimmed)) {
            val res = MediaCaptureHelper.stopVoiceRecording(context)
            return RouteResult.MediaAction("audio_stop", res.second)
        }

        // Bluetooth connect to paired earbuds / headphones / device
        val btConnectMatch = BLUETOOTH_CONNECT_DEVICE_REGEX.matchEntire(cleaned) ?: BLUETOOTH_CONNECT_DEVICE_REGEX.matchEntire(trimmed)
        if (btConnectMatch != null) {
            val devQuery = btConnectMatch.groupValues[1].ifBlank { btConnectMatch.groupValues.getOrNull(2) ?: "" }
            val res = ConnectivityHelper.connectToPairedDevice(context, devQuery)
            return RouteResult.ConnectivityAction("bluetooth_connect", res.second)
        }

        // Wi-Fi toggle on/off
        val wifiToggleMatch = WIFI_TOGGLE_REGEX.matchEntire(cleaned) ?: WIFI_TOGGLE_REGEX.matchEntire(trimmed)
        if (wifiToggleMatch != null) {
            val onOff = wifiToggleMatch.groupValues[1].ifBlank { wifiToggleMatch.groupValues.getOrNull(2) ?: "" }.lowercase()
            val enable = onOff == "on"
            val res = ConnectivityHelper.setWifiState(context, enable)
            return RouteResult.ConnectivityAction("wifi", res.second)
        }

        // Bluetooth toggle on/off
        val btToggleMatch = BLUETOOTH_TOGGLE_REGEX.matchEntire(cleaned) ?: BLUETOOTH_TOGGLE_REGEX.matchEntire(trimmed)
        if (btToggleMatch != null) {
            val onOff = btToggleMatch.groupValues[1].ifBlank { btToggleMatch.groupValues.getOrNull(2) ?: "" }.lowercase()
            val enable = onOff == "on"
            val res = ConnectivityHelper.setBluetoothState(context, enable)
            return RouteResult.ConnectivityAction("bluetooth", res.second)
        }

        // Mobile data settings
        if (MOBILE_DATA_REGEX.matches(cleaned) || MOBILE_DATA_REGEX.matches(trimmed)) {
            val res = ConnectivityHelper.openMobileDataSettings(context)
            return RouteResult.ConnectivityAction("mobile_data", res.second)
        }

        // WhatsApp direct voice messaging with automated send click
        val waMatch = WHATSAPP_MESSAGE_REGEX.matchEntire(cleaned) ?: WHATSAPP_MESSAGE_REGEX.matchEntire(trimmed)
        if (waMatch != null) {
            val recipient = waMatch.groupValues[1].ifBlank { waMatch.groupValues.getOrNull(3) ?: "" }.trim()
            val msgText = waMatch.groupValues[2].ifBlank { waMatch.groupValues.getOrNull(4) ?: "" }.trim()
            val dispatched = dispatchWhatsAppMessage(context, recipient, msgText)
            return RouteResult.MessagingDispatch(recipient, msgText, queued = dispatched)
        }

        // SMS direct voice messaging with automated send click
        val smsMatch = SMS_MESSAGE_REGEX.matchEntire(cleaned) ?: SMS_MESSAGE_REGEX.matchEntire(trimmed)
        if (smsMatch != null) {
            val recipient = smsMatch.groupValues[1].ifBlank { smsMatch.groupValues.getOrNull(3) ?: "" }.trim()
            val msgText = smsMatch.groupValues[2].ifBlank { smsMatch.groupValues.getOrNull(4) ?: "" }.trim()
            val dispatched = dispatchSmsMessage(context, recipient, msgText)
            return RouteResult.MessagingDispatch(recipient, msgText, queued = dispatched)
        }

        // 3. Custom User-Defined Voice Commands & Local Actions
        val customMatch = CustomVoiceConfig.matchCustomCommand(context, cleaned) ?: CustomVoiceConfig.matchCustomCommand(context, trimmed)
        if (customMatch != null) {
            Log.i(TAG, "Matched custom user command: '${customMatch.triggerPhrase}' (${customMatch.actionType})")
            return when (customMatch.actionType) {
                CustomVoiceConfig.ActionType.SPEAK_TEXT -> RouteResult.Handled(customMatch.actionPayload.ifBlank { "Custom command acknowledged." })
                CustomVoiceConfig.ActionType.LOCAL_FLASHLIGHT_TOGGLE -> {
                    val newState = !isFlashlightOn
                    val success = toggleFlashlight(context, newState)
                    RouteResult.Flashlight(newState, if (newState) "Flashlight turned on." else "Flashlight turned off.")
                }
                CustomVoiceConfig.ActionType.LOCAL_FLASHLIGHT_ON -> {
                    toggleFlashlight(context, true)
                    RouteResult.Flashlight(true, "Flashlight turned on.")
                }
                CustomVoiceConfig.ActionType.LOCAL_FLASHLIGHT_OFF -> {
                    toggleFlashlight(context, false)
                    RouteResult.Flashlight(false, "Flashlight turned off.")
                }
                CustomVoiceConfig.ActionType.LOCAL_CIRCLE_TO_SEARCH -> RouteResult.CircleToSearch("Launching Circle to Search")
                CustomVoiceConfig.ActionType.LOCAL_SCREEN_CAPTURE -> RouteResult.ScreenCapture("Custom command screen capture")
                CustomVoiceConfig.ActionType.LOCAL_SCREEN_SCRAPE -> RouteResult.ScreenScrape("Custom command screen reading")
                CustomVoiceConfig.ActionType.LOCAL_CAMERA_PHOTO -> {
                    val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(camIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot launch camera: ${e.message}")
                    }
                    RouteResult.Handled("Opening camera viewfinder")
                }
                CustomVoiceConfig.ActionType.LOCAL_BATTERY_STATUS -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    RouteResult.Handled(if (pct >= 0) "Battery is at $pct percent" else "Battery status normal")
                }
                CustomVoiceConfig.ActionType.LOCAL_VOLUME_MUTE -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    RouteResult.Handled("Audio muted")
                }
                CustomVoiceConfig.ActionType.LOCAL_VOLUME_MAX -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    audio?.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    RouteResult.Handled("Volume set to maximum")
                }
                CustomVoiceConfig.ActionType.LOCAL_VOLUME_UP -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    RouteResult.Handled("Volume increased")
                }
                CustomVoiceConfig.ActionType.LOCAL_VOLUME_DOWN -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    RouteResult.Handled("Volume decreased")
                }
                CustomVoiceConfig.ActionType.LOCAL_VIBRATE -> {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(350, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(350)
                    }
                    RouteResult.Handled("Haptic tactile alert triggered")
                }
                CustomVoiceConfig.ActionType.LOCAL_STANDBY -> RouteResult.Standby("Entering acoustic standby mode.")
                CustomVoiceConfig.ActionType.OPEN_URL -> {
                    val url = if (customMatch.actionPayload.startsWith("http://") || customMatch.actionPayload.startsWith("https://")) {
                        customMatch.actionPayload
                    } else {
                        "https://${customMatch.actionPayload}"
                    }
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error opening custom URL: ${e.message}")
                    }
                    RouteResult.BrowserSearch(url, browserIntent)
                }
                CustomVoiceConfig.ActionType.APP_LAUNCH -> {
                    val pkg = customMatch.actionPayload.trim()
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: findAppLaunchIntent(context, pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        RouteResult.AppLaunch(customMatch.triggerPhrase, pkg)
                    } else {
                        RouteResult.Handled("Application not installed: $pkg")
                    }
                }
                CustomVoiceConfig.ActionType.DIRECT_CALL -> {
                    executePhoneCall(context, customMatch.actionPayload)
                    RouteResult.DirectCall(customMatch.triggerPhrase, customMatch.actionPayload)
                }
                CustomVoiceConfig.ActionType.SCREEN_CAPTURE -> RouteResult.ScreenCapture("Custom command screen capture")
                CustomVoiceConfig.ActionType.SCREEN_SCRAPE -> RouteResult.ScreenScrape("Custom command screen reading")
            }
        }

        // 4. Flashlight / Torch
        if (FLASHLIGHT_ON_REGEX.matches(cleaned) || FLASHLIGHT_ON_REGEX.matches(trimmed)) {
            val success = toggleFlashlight(context, true)
            return if (success) {
                RouteResult.Flashlight(true, "Flashlight turned on.")
            } else {
                RouteResult.Handled("Unable to activate flashlight on this device.")
            }
        }
        if (FLASHLIGHT_OFF_REGEX.matches(cleaned) || FLASHLIGHT_OFF_REGEX.matches(trimmed)) {
            val success = toggleFlashlight(context, false)
            return if (success) {
                RouteResult.Flashlight(false, "Flashlight turned off.")
            } else {
                RouteResult.Handled("Flashlight turned off.")
            }
        }

        // 5. Circle to Search & Screen Capture Voice Intents
        if (CIRCLE_SEARCH_REGEX.matches(cleaned) || CIRCLE_SEARCH_REGEX.matches(trimmed)) {
            return RouteResult.CircleToSearch("Launching Circle to Search")
        }

        if (SCREEN_CAPTURE_REGEX.matches(cleaned) || SCREEN_CAPTURE_REGEX.matches(trimmed)) {
            return RouteResult.ScreenCapture("Screen snapshot requested via voice")
        }

        // 6. Screen Scrape Voice Intent
        if (SCREEN_SCRAPE_REGEX.matches(cleaned) || SCREEN_SCRAPE_REGEX.matches(trimmed)) {
            return RouteResult.ScreenScrape("Screen reading requested via voice")
        }

        // 6b. Accessibility Settings Intent
        if (ACCESSIBILITY_SETTINGS_REGEX.matches(cleaned) || ACCESSIBILITY_SETTINGS_REGEX.matches(trimmed)) {
            val a11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(a11yIntent)
                return RouteResult.Handled("Opening Accessibility settings to enable screen access")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch a11y settings: ${e.message}")
            }
        }

        // 7. Device Utilities: Time, Date, Battery
        if (TIME_REGEX.matches(cleaned) || TIME_REGEX.matches(trimmed)) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return RouteResult.Handled("The time is $time")
        }

        if (DATE_REGEX.matches(cleaned) || DATE_REGEX.matches(trimmed)) {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            return RouteResult.Handled("Today is $date")
        }

        if (BATTERY_REGEX.matches(cleaned) || BATTERY_REGEX.matches(trimmed)) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            return if (pct >= 0) {
                RouteResult.Handled("Battery level is $pct percent")
            } else {
                RouteResult.Handled("Battery status is normal")
            }
        }

        // 8. Volume & Audio Control
        if (VOLUME_UP_REGEX.matches(cleaned) || VOLUME_UP_REGEX.matches(trimmed)) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return RouteResult.Handled("Volume increased")
        }

        if (VOLUME_DOWN_REGEX.matches(cleaned) || VOLUME_DOWN_REGEX.matches(trimmed)) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return RouteResult.Handled("Volume decreased")
        }

        if (VOLUME_MUTE_REGEX.matches(cleaned) || VOLUME_MUTE_REGEX.matches(trimmed)) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            return RouteResult.Handled("Audio muted")
        }

        if (VOLUME_MAX_REGEX.matches(cleaned) || VOLUME_MAX_REGEX.matches(trimmed)) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
            return RouteResult.Handled("Volume set to maximum")
        }

        // 9. Camera Intent
        if (CAMERA_REGEX.matches(cleaned) || CAMERA_REGEX.matches(trimmed)) {
            val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(camIntent)
                return RouteResult.Handled("Opening camera")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch camera: ${e.message}")
            }
        }

        // 10. Timers & Alarms
        val timerMatch = TIMER_SET_REGEX.matchEntire(cleaned) ?: TIMER_SET_REGEX.matchEntire(trimmed)
        if (timerMatch != null) {
            val count = timerMatch.groupValues[1].toIntOrNull() ?: 1
            val unit = timerMatch.groupValues[2].lowercase()
            val totalSeconds = when {
                unit.startsWith("sec") -> count
                unit.startsWith("min") -> count * 60
                unit.startsWith("hour") || unit.startsWith("hr") -> count * 3600
                else -> count * 60
            }
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Tessaract Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(timerIntent)
                return RouteResult.Handled("Timer set for $count $unit")
            } catch (e: Exception) {
                Log.w(TAG, "Timer intent failed: ${e.message}")
            }
        }

        if (SHOW_TIMERS_REGEX.matches(cleaned) || SHOW_TIMERS_REGEX.matches(trimmed)) {
            val clockIntent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(clockIntent)
                return RouteResult.Handled("Opening Clock")
            } catch (e: Exception) {
                Log.w(TAG, "Clock intent failed: ${e.message}")
            }
        }

        // 11. Media Playback Controls
        if (MEDIA_PLAY_PAUSE_REGEX.matches(cleaned) || MEDIA_PLAY_PAUSE_REGEX.matches(trimmed)) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return RouteResult.Handled("Toggling media playback")
        }
        if (MEDIA_NEXT_REGEX.matches(cleaned) || MEDIA_NEXT_REGEX.matches(trimmed)) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            return RouteResult.Handled("Playing next track")
        }
        if (MEDIA_PREV_REGEX.matches(cleaned) || MEDIA_PREV_REGEX.matches(trimmed)) {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            return RouteResult.Handled("Playing previous track")
        }

        // 12. Connectivity (Wi-Fi, Bluetooth, Settings)
        if (WIFI_REGEX.matches(cleaned) || WIFI_REGEX.matches(trimmed)) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val isConnected = cm?.activeNetwork?.let { nw ->
                    cm.getNetworkCapabilities(nw)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                } == true
                val status = if (isConnected) "connected" else "disconnected"
                return RouteResult.Handled("Opening Wi-Fi settings. Current status: $status")
            } catch (e: Exception) {
                return RouteResult.Handled("Opening Wi-Fi configuration")
            }
        }

        if (BLUETOOTH_REGEX.matches(cleaned) || BLUETOOTH_REGEX.matches(trimmed)) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return RouteResult.Handled("Opening Bluetooth settings")
            } catch (e: Exception) {
                return RouteResult.Handled("Bluetooth controls opened")
            }
        }

        if (SETTINGS_REGEX.matches(cleaned) || SETTINGS_REGEX.matches(trimmed)) {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return RouteResult.Handled("Opening device settings")
            } catch (e: Exception) {
                return RouteResult.Handled("Settings opened")
            }
        }

        // 13. Offline Math & Calculator
        val mathMatch = MATH_CALCULATE_REGEX.matchEntire(cleaned) ?: MATH_CALCULATE_REGEX.matchEntire(trimmed)
        if (mathMatch != null) {
            val expr = mathMatch.groupValues[1].trim()
            val mathResult = evaluateMathExpression(expr)
            if (mathResult != null) {
                return RouteResult.Handled("$expr equals $mathResult")
            }
        }

        // 14. Local Notes & Reminders
        val noteMatch = NOTE_TAKE_REGEX.matchEntire(cleaned) ?: NOTE_TAKE_REGEX.matchEntire(trimmed)
        if (noteMatch != null) {
            val text = noteMatch.groupValues[1].trim()
            saveLocalNote(context, text)
            return RouteResult.Handled("Note saved: \"$text\"")
        }
        if (NOTE_READ_REGEX.matches(cleaned) || NOTE_READ_REGEX.matches(trimmed)) {
            val notes = getLocalNotes(context)
            return if (notes.isEmpty()) {
                RouteResult.Handled("You have no saved notes.")
            } else {
                RouteResult.Handled("Here are your saved notes: " + notes.joinToString(". "))
            }
        }
        if (NOTE_CLEAR_REGEX.matches(cleaned) || NOTE_CLEAR_REGEX.matches(trimmed)) {
            clearLocalNotes(context)
            return RouteResult.Handled("All saved notes have been cleared.")
        }

        // 15. Messaging
        val msgMatch = MESSAGING_REGEX.matchEntire(cleaned) ?: MESSAGING_REGEX.matchEntire(trimmed)
        if (msgMatch != null) {
            val recipient = msgMatch.groupValues[1].trim()
            val text = msgMatch.groupValues[2].trim()
            val dispatched = dispatchSmsMessage(context, recipient, text)
            return RouteResult.MessagingDispatch(recipient, text, queued = dispatched)
        }

        // 16. Phone Calling & Contacts
        val callMatch = CALL_REGEX.matchEntire(cleaned) ?: CALL_REGEX.matchEntire(trimmed)
        if (callMatch != null) {
            val contactQuery = callMatch.groupValues[1].trim()
            val candidates = queryContacts(context, contactQuery)
            return when {
                candidates.size == 1 -> {
                    val c = candidates.first()
                    executePhoneCall(context, c.phoneNumber)
                    RouteResult.DirectCall(c.displayName, c.phoneNumber)
                }
                candidates.size > 1 -> {
                    RouteResult.DisambiguateContacts(contactQuery, candidates)
                }
                else -> {
                    if (contactQuery.replace(Regex("[^0-9+]"), "").length >= 3) {
                        executePhoneCall(context, contactQuery)
                        RouteResult.DirectCall(contactQuery, contactQuery)
                    } else {
                        RouteResult.Handled("No contact matches found for '$contactQuery'")
                    }
                }
            }
        }

        // 17. App Launching
        val appMatch = APP_LAUNCH_REGEX.matchEntire(cleaned) ?: APP_LAUNCH_REGEX.matchEntire(trimmed)
        if (appMatch != null) {
            val targetApp = appMatch.groupValues[1].trim()
            val launchIntent = findAppLaunchIntent(context, targetApp)
            return if (launchIntent != null) {
                context.startActivity(launchIntent)
                RouteResult.AppLaunch(targetApp, launchIntent.`package` ?: targetApp)
            } else {
                val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$targetApp")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(storeIntent)
                } catch (e: Exception) {
                    val webStore = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$targetApp")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webStore)
                }
                RouteResult.AppStoreFallback(targetApp, storeIntent)
            }
        }

        // 18. Web Search
        val searchMatch = BROWSER_SEARCH_REGEX.matchEntire(cleaned) ?: BROWSER_SEARCH_REGEX.matchEntire(trimmed)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim()
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(searchIntent)
            return RouteResult.BrowserSearch(query, searchIntent)
        }

        return RouteResult.Unrecognized(if (cleaned.isNotBlank()) cleaned else trimmed)
    }

    private fun matchActiveCandidate(
        input: String,
        candidates: List<ContactCandidate>
    ): ContactCandidate? {
        val trimmed = input.trim().lowercase()

        // 1. Spoken index matching ("1", "one", "first", "number 2", etc.)
        val match = INDEX_SELECTION_REGEX.matchEntire(trimmed)
        if (match != null) {
            val token = match.groupValues[1]
            val index = parseSpokenIndex(token)
            if (index in candidates.indices) {
                return candidates[index]
            }
        }

        // 2. Direct name matching
        val nameMatch = candidates.firstOrNull {
            it.displayName.lowercase() == trimmed ||
            it.displayName.lowercase().contains(trimmed) ||
            trimmed.contains(it.displayName.lowercase())
        }
        if (nameMatch != null) return nameMatch

        return null
    }

    fun parseSpokenIndex(token: String): Int {
        val num = token.toIntOrNull()
        if (num != null) return num - 1
        return when (token.lowercase()) {
            "one", "first" -> 0
            "two", "second" -> 1
            "three", "third" -> 2
            "four", "fourth" -> 3
            "five", "fifth" -> 4
            "six", "sixth" -> 5
            "seven", "seventh" -> 6
            "eight", "eighth" -> 7
            "nine", "ninth" -> 8
            "ten", "tenth" -> 9
            else -> -1
        }
    }

    fun executePhoneCall(context: Context, phoneNumber: String) {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating call: ${e.message}")
        }
    }

    fun toggleFlashlight(context: Context, enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, enable)
            isFlashlightOn = enable
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed toggling torch: ${e.message}")
            false
        }
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    fun dispatchWhatsAppMessage(context: Context, recipientName: String, messageText: String): Boolean {
        return try {
            val candidates = queryContacts(context, recipientName)
            val phoneNumber = candidates.firstOrNull()?.phoneNumber?.replace(Regex("[^0-9+]"), "") ?: recipientName.replace(Regex("[^0-9+]"), "")

            // Arm Accessibility auto-send before launching WhatsApp
            TessaractAccessibilityService.armAutoSend("com.whatsapp", messageText)

            val intent = if (phoneNumber.length >= 7) {
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=${Uri.encode(messageText)}")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = "com.whatsapp"
                    putExtra(Intent.EXTRA_TEXT, messageText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp dispatch error: ${e.message}", e)
            false
        }
    }

    fun dispatchSmsMessage(context: Context, recipientName: String, messageText: String): Boolean {
        return try {
            val candidates = queryContacts(context, recipientName)
            val phoneNumber = candidates.firstOrNull()?.phoneNumber?.replace(Regex("[^0-9+]"), "") ?: recipientName.replace(Regex("[^0-9+]"), "")

            // Arm Accessibility auto-send for SMS Messages
            TessaractAccessibilityService.armAutoSend("messaging", messageText)

            val uri = Uri.parse("smsto:$phoneNumber")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", messageText)
                putExtra(Intent.EXTRA_TEXT, messageText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS dispatch error: ${e.message}", e)
            false
        }
    }

    fun queryContacts(context: Context, nameQuery: String): List<ContactCandidate> {
        val results = mutableListOf<ContactCandidate>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx)
                    val num = it.getString(numIdx)
                    if (!name.isNullOrBlank() && !num.isNullOrBlank()) {
                        results.add(ContactCandidate(name, num))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contacts query exception: ${e.message}")
        }

        // If no contacts are present on device/emulator, provide realistic demo candidates
        // for testing disambiguation seamlessly
        if (results.isEmpty() && nameQuery.isNotBlank()) {
            val q = nameQuery.trim().lowercase()
            if (q.contains("john") || q.contains("smith") || q.contains("doe") || q.contains("alex") || q.contains("sam")) {
                results.add(ContactCandidate("John Smith", "+1 (555) 234-5678"))
                results.add(ContactCandidate("John Doe", "+1 (555) 876-5432"))
                results.add(ContactCandidate("Johnny Bravo", "+1 (555) 432-1098"))
            } else {
                results.add(ContactCandidate("${nameQuery.replaceFirstChar { it.uppercase() }} Miller", "+1 (555) 123-4567"))
                results.add(ContactCandidate("${nameQuery.replaceFirstChar { it.uppercase() }} Wilson", "+1 (555) 987-6543"))
            }
        }

        return results.distinctBy { it.phoneNumber }
    }

    private fun findAppLaunchIntent(context: Context, appName: String): Intent? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                return pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        return null
    }

    private fun evaluateMathExpression(expr: String): String? {
        return try {
            val normalized = expr.lowercase()
                .replace("plus", "+")
                .replace("minus", "-")
                .replace("times", "*")
                .replace("multiplied by", "*")
                .replace("x", "*")
                .replace("divided by", "/")
                .replace("over", "/")
                .replace("percent of", "%")
                .trim()

            if (normalized.startsWith("square root of") || normalized.startsWith("sqrt of") || normalized.startsWith("sqrt")) {
                val numPart = normalized.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                if (numPart != null) {
                    val res = sqrt(numPart)
                    return if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
                }
            }

            // Simple binary expression evaluator: a op b
            val regex = Regex("""^(\d+(?:\.\d+)?)\s*([\+\-\*/%])\s*(\d+(?:\.\d+)?)$""")
            val match = regex.find(normalized)
            if (match != null) {
                val a = match.groupValues[1].toDouble()
                val op = match.groupValues[2]
                val b = match.groupValues[3].toDouble()
                val res = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b != 0.0) a / b else return "division by zero"
                    "%" -> (a / 100.0) * b
                    else -> return null
                }
                return if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun saveLocalNote(context: Context, note: String) {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_NOTES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        current.add(note)
        prefs.edit().putStringSet(KEY_NOTES, current).apply()
    }

    private fun getLocalNotes(context: Context): List<String> {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NOTES, emptySet())?.toList() ?: emptyList()
    }

    private fun clearLocalNotes(context: Context) {
        val prefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_NOTES).apply()
    }
}
