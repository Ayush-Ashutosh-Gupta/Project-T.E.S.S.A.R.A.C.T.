package com.example.tessaract

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Project T.E.S.S.A.R.A.C.T. - Custom Voice Configuration
 * Manages persistent storage and matching for:
 * 1. Custom Wake Words (e.g., "Hey Jarvis", "Computer", "Friday", "Nova", "Tesseract")
 * 2. Custom Local Voice Commands (custom triggers mapped to Flashlight, Volume, Circle Search,
 *    Screenshots, Screen Scrape, Camera, Battery, Haptics, App Launches, URLs, Text Responses, Direct Calls)
 */
object CustomVoiceConfig {

    private const val TAG = "CustomVoiceConfig"
    private const val PREFS_NAME = "tessaract_custom_voice_prefs"
    private const val KEY_WAKE_WORDS = "config_custom_wake_words"
    private const val KEY_CUSTOM_COMMANDS = "config_custom_commands"

    val DEFAULT_WAKE_WORDS = listOf(
        "hey tesseract",
        "tesseract",
        "hey tessaract",
        "tessaract",
        "wake up",
        "computer",
        "jarvis",
        "friday",
        "nova"
    )

    enum class ActionType(val displayName: String, val category: String) {
        // Custom Text Response
        SPEAK_TEXT("Speak Response", "Custom Vocal"),
        // Local Hardware & System
        LOCAL_FLASHLIGHT_TOGGLE("Toggle Flashlight", "Local Device"),
        LOCAL_FLASHLIGHT_ON("Turn Flashlight ON", "Local Device"),
        LOCAL_FLASHLIGHT_OFF("Turn Flashlight OFF", "Local Device"),
        LOCAL_BATTERY_STATUS("Check Battery Level", "Local Device"),
        LOCAL_VIBRATE("Haptic Vibration", "Local Device"),
        LOCAL_STANDBY("Go to Sleep / Standby", "Assistant"),
        // Local Audio & Volume
        LOCAL_VOLUME_MUTE("Mute Audio", "Audio & Sound"),
        LOCAL_VOLUME_MAX("Max Volume", "Audio & Sound"),
        LOCAL_VOLUME_UP("Volume Up", "Audio & Sound"),
        LOCAL_VOLUME_DOWN("Volume Down", "Audio & Sound"),
        // Local Screen & Vision
        LOCAL_CIRCLE_TO_SEARCH("Circle to Search", "Screen Vision"),
        LOCAL_SCREEN_CAPTURE("Take Screenshot", "Screen Vision"),
        LOCAL_SCREEN_SCRAPE("Read Screen Text", "Screen Vision"),
        LOCAL_CAMERA_PHOTO("Open Camera", "Camera & Media"),
        // External & Apps
        APP_LAUNCH("Launch App", "App & Web"),
        OPEN_URL("Open Website / URL", "App & Web"),
        DIRECT_CALL("Direct Phone Call", "Phone & Contacts"),
        // Legacy aliases
        SCREEN_CAPTURE("Take Screenshot", "Screen Vision"),
        SCREEN_SCRAPE("Read Screen Text", "Screen Vision")
    }

    data class CustomCommand(
        val id: String = UUID.randomUUID().toString(),
        val triggerPhrase: String,
        val actionType: ActionType,
        val actionPayload: String = "",
        val description: String = ""
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // -------------------------------------------------------------
    // Wake Words Management
    // -------------------------------------------------------------

    fun getWakeWords(context: Context): List<String> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_WAKE_WORDS, null)
        if (raw.isNullOrBlank()) {
            return DEFAULT_WAKE_WORDS
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim().lowercase()
                if (item.isNotBlank() && !list.contains(item)) {
                    list.add(item)
                }
            }
            if (list.isEmpty()) DEFAULT_WAKE_WORDS else list
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing wake words: ${e.message}")
            DEFAULT_WAKE_WORDS
        }
    }

    fun saveWakeWords(context: Context, words: List<String>) {
        val cleanList = words.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val arr = JSONArray(cleanList)
        getPrefs(context).edit().putString(KEY_WAKE_WORDS, arr.toString()).apply()
    }

    fun addWakeWord(context: Context, newWord: String): Boolean {
        val clean = newWord.trim().lowercase()
        if (clean.length < 2) return false
        val current = getWakeWords(context).toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            saveWakeWords(context, current)
            return true
        }
        return false
    }

    fun removeWakeWord(context: Context, wordToRemove: String): Boolean {
        val clean = wordToRemove.trim().lowercase()
        val current = getWakeWords(context).toMutableList()
        val removed = current.remove(clean)
        if (removed) {
            if (current.isEmpty()) {
                saveWakeWords(context, DEFAULT_WAKE_WORDS)
            } else {
                saveWakeWords(context, current)
            }
        }
        return removed
    }

    private val PHONETIC_WAKE_ALIASES = listOf(
        "hey tesseract", "tesseract", "hey tessaract", "tessaract",
        "hey tesaract", "tesaract", "hey tasseract", "tasseract",
        "hey tess react", "tess react", "hey test react", "test react",
        "hey tess direct", "tess direct", "tess aract", "the tesseract",
        "hey tess", "hey tessa",
        "wake up", "wake up tesseract", "computer", "jarvis", "friday", "nova", "assistant"
    )

    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun isWakePhrase(context: Context, spokenText: String): Boolean {
        return extractWakeAndCommand(context, spokenText) != null
    }

    /**
     * Detects if spoken speech begins with or contains any active wake word (default, custom, or phonetic alias),
     * and extracts the wake word and any trailing command.
     * Includes word-boundary validation and fuzzy Levenshtein matching for speech-to-text tolerance.
     */
    fun extractWakeAndCommand(context: Context, spokenText: String): Pair<String, String>? {
        val normalized = spokenText.trim().lowercase()
        if (normalized.isBlank()) return null

        val activeWakeWords = getWakeWords(context)
        val allWakeTargets = (activeWakeWords + PHONETIC_WAKE_ALIASES).distinct().sortedByDescending { it.length }
        val greetings = listOf("hey", "ok", "okay", "hi", "hello", "yo")

        // 1. Exact or prefix match against all wake targets and aliases with strict word boundaries
        for (target in allWakeTargets) {
            val clean = target.trim().lowercase()
            if (clean.isBlank()) continue

            if (normalized == clean) {
                return Pair(target, "")
            }

            if (normalized.startsWith(clean) && (normalized.length == clean.length || !normalized[clean.length].isLetterOrDigit())) {
                val remainder = normalized.substring(clean.length).trim().trimStart(',', ':', '-', '.')
                return Pair(target, remainder)
            }

            for (greeting in greetings) {
                val greetPrefix = "$greeting $clean"
                if (normalized == greetPrefix) {
                    return Pair(greetPrefix, "")
                }
                if (normalized.startsWith(greetPrefix) && (normalized.length == greetPrefix.length || !normalized[greetPrefix.length].isLetterOrDigit())) {
                    val remainder = normalized.substring(greetPrefix.length).trim().trimStart(',', ':', '-', '.')
                    return Pair(greetPrefix, remainder)
                }
            }

            var searchFrom = 0
            while (searchFrom <= normalized.length - clean.length) {
                val idx = normalized.indexOf(clean, searchFrom)
                if (idx < 0) break
                val beforeOk = idx == 0 || !normalized[idx - 1].isLetterOrDigit()
                val afterIndex = idx + clean.length
                val afterOk = afterIndex == normalized.length || !normalized[afterIndex].isLetterOrDigit()
                if (beforeOk && afterOk) {
                    val remainder = normalized.substring(afterIndex).trim().trimStart(',', ':', '-', '.')
                    return Pair(target, remainder)
                }
                searchFrom = idx + 1
            }
        }

        // 2. Fuzzy Levenshtein token match across utterance tokens
        val tokens = normalized.split("\\s+".toRegex())
        for (token in tokens) {
            val cleanToken = token.trim(',', '.', '!', '?', '-', ':')
            if (cleanToken.length < 3) continue

            for (target in activeWakeWords) {
                val cleanTarget = target.trim().lowercase()
                val threshold = when {
                    cleanTarget.length >= 7 -> 2
                    cleanTarget.length >= 4 -> 1
                    else -> 0
                }
                if (threshold > 0 && levenshteinDistance(cleanToken, cleanTarget) <= threshold) {
                    val targetPos = normalized.indexOf(token)
                    val remainder = if (targetPos >= 0) {
                        normalized.substring(targetPos + token.length).trim().trimStart(',', ':', '-', '.')
                    } else ""
                    return Pair(target, remainder)
                }
            }
        }

        return null
    }

    /**
     * Strips ANY active wake word prefix from the spoken text so subsequent command matchers
     * can cleanly match local or custom commands.
     */
    fun stripWakeWord(context: Context, spokenText: String): String {
        val normalized = spokenText.trim()
        val extract = extractWakeAndCommand(context, normalized)
        if (extract != null && extract.second.isNotBlank()) {
            return extract.second
        }
        val genericRegex = Regex("""^(?:(?:hey|ok|okay|hi|hello|yo)\s+)?(?:tess[ea]ract|computer|jarvis|assistant)\b[\s,:\-]*""", RegexOption.IGNORE_CASE)
        val cleaned = normalized.replace(genericRegex, "").trim()
        return if (cleaned.isNotEmpty()) cleaned else normalized
    }

    // -------------------------------------------------------------
    // Custom Voice Commands Management
    // -------------------------------------------------------------

    fun getCustomCommands(context: Context): List<CustomCommand> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_CUSTOM_COMMANDS, null)
        if (raw.isNullOrBlank()) {
            val defaults = getDefaultCommands()
            persistCommands(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CustomCommand>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionTypeStr = obj.optString("actionType", ActionType.SPEAK_TEXT.name)
                val type = try {
                    ActionType.valueOf(actionTypeStr)
                } catch (e: Exception) {
                    ActionType.SPEAK_TEXT
                }
                list.add(
                    CustomCommand(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        triggerPhrase = obj.getString("triggerPhrase"),
                        actionType = type,
                        actionPayload = obj.optString("actionPayload", ""),
                        description = obj.optString("description", "")
                    )
                )
            }
            if (list.isEmpty()) {
                val defaults = getDefaultCommands()
                persistCommands(context, defaults)
                defaults
            } else {
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing custom commands: ${e.message}")
            getDefaultCommands()
        }
    }

    fun saveCustomCommand(context: Context, command: CustomCommand) {
        val commands = getCustomCommands(context).toMutableList()
        val index = commands.indexOfFirst { it.id == command.id }
        if (index >= 0) {
            commands[index] = command
        } else {
            commands.add(command)
        }
        persistCommands(context, commands)
    }

    fun deleteCustomCommand(context: Context, commandId: String): Boolean {
        val commands = getCustomCommands(context).toMutableList()
        val removed = commands.removeAll { it.id == commandId }
        if (removed) {
            persistCommands(context, commands)
        }
        return removed
    }

    private fun persistCommands(context: Context, commands: List<CustomCommand>) {
        val arr = JSONArray()
        for (cmd in commands) {
            val obj = JSONObject().apply {
                put("id", cmd.id)
                put("triggerPhrase", cmd.triggerPhrase.trim().lowercase())
                put("actionType", cmd.actionType.name)
                put("actionPayload", cmd.actionPayload)
                put("description", cmd.description)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_CUSTOM_COMMANDS, arr.toString()).apply()
    }

    fun matchCustomCommand(context: Context, spokenText: String): CustomCommand? {
        val normalized = spokenText.trim().lowercase().trimEnd('.', '!', '?')
        if (normalized.isBlank()) return null
        val commands = getCustomCommands(context)
        val sorted = commands.sortedByDescending { it.triggerPhrase.length }
        for (cmd in sorted) {
            val trigger = cmd.triggerPhrase.trim().lowercase().trimEnd('.', '!', '?')
            if (trigger.isBlank()) continue
            if (normalized == trigger ||
                normalized.startsWith("$trigger ") ||
                normalized.endsWith(" $trigger") ||
                normalized.contains(" $trigger ") ||
                normalized.contains(trigger)) {
                return cmd
            }
        }
        return null
    }

    fun getDefaultCommands(): List<CustomCommand> {
        return listOf(
            CustomCommand(
                triggerPhrase = "system status",
                actionType = ActionType.SPEAK_TEXT,
                actionPayload = "All cybernetic systems operating at optimal acoustic parameters.",
                description = "Speaks system health diagnostics"
            ),
            CustomCommand(
                triggerPhrase = "night light",
                actionType = ActionType.LOCAL_FLASHLIGHT_TOGGLE,
                description = "Toggles flashlight on or off"
            ),
            CustomCommand(
                triggerPhrase = "torch on",
                actionType = ActionType.LOCAL_FLASHLIGHT_ON,
                description = "Turns on flashlight"
            ),
            CustomCommand(
                triggerPhrase = "torch off",
                actionType = ActionType.LOCAL_FLASHLIGHT_OFF,
                description = "Turns off flashlight"
            ),
            CustomCommand(
                triggerPhrase = "circle search",
                actionType = ActionType.LOCAL_CIRCLE_TO_SEARCH,
                description = "Launches Circle to Search overlay"
            ),
            CustomCommand(
                triggerPhrase = "snapshot",
                actionType = ActionType.LOCAL_SCREEN_CAPTURE,
                description = "Instant screen capture"
            ),
            CustomCommand(
                triggerPhrase = "read screen",
                actionType = ActionType.LOCAL_SCREEN_SCRAPE,
                description = "Extracts visible screen text via Accessibility"
            ),
            CustomCommand(
                triggerPhrase = "snap photo",
                actionType = ActionType.LOCAL_CAMERA_PHOTO,
                description = "Opens camera viewfinder"
            ),
            CustomCommand(
                triggerPhrase = "battery check",
                actionType = ActionType.LOCAL_BATTERY_STATUS,
                description = "Reports device battery percentage"
            ),
            CustomCommand(
                triggerPhrase = "silence audio",
                actionType = ActionType.LOCAL_VOLUME_MUTE,
                description = "Mutes device media volume"
            ),
            CustomCommand(
                triggerPhrase = "max volume",
                actionType = ActionType.LOCAL_VOLUME_MAX,
                description = "Sets volume to 100%"
            ),
            CustomCommand(
                triggerPhrase = "haptic alert",
                actionType = ActionType.LOCAL_VIBRATE,
                description = "Triggers a tactile vibration"
            ),
            CustomCommand(
                triggerPhrase = "launch chrome",
                actionType = ActionType.APP_LAUNCH,
                actionPayload = "com.android.chrome",
                description = "Launches Google Chrome browser"
            )
        )
    }
}
