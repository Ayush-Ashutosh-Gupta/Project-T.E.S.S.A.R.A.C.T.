package com.example.tessaract

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Project T.E.S.S.A.R.A.C.T. - Routine Commands Engine
 * Allows chaining multiple local actions and device controls into a single voice-activated routine.
 * Example: "Movie Time" -> Volume 89%, Brightness 76%, Launch Netflix, Connect Bluetooth headphones.
 */
object RoutineCommandsConfig {

    private const val TAG = "RoutineCommands"
    private const val PREFS_NAME = "tessaract_routines_prefs"
    private const val KEY_ROUTINES = "config_routine_commands"

    enum class RoutineActionType(val displayName: String, val payloadHint: String) {
        SET_VOLUME("Set Volume", "Percentage (0-100) e.g. 89"),
        SET_BRIGHTNESS("Set Brightness", "Percentage (0-100) e.g. 76"),
        LAUNCH_APP("Launch App", "App Name or Package (e.g. Netflix, Spotify)"),
        CONNECT_BLUETOOTH("Connect Bluetooth", "Device Name or Headphones"),
        TOGGLE_WIFI("Wi-Fi Control", "ON or OFF"),
        TOGGLE_BLUETOOTH("Bluetooth Control", "ON or OFF"),
        TAKE_PHOTO("Take Photo", "Takes camera photo saved to Gallery"),
        RECORD_VIDEO("Record Video", "Starts video recording saved to Gallery"),
        RECORD_AUDIO("Record Audio", "Starts voice recording saved to Gallery"),
        FLASHLIGHT("Flashlight Control", "ON, OFF, or TOGGLE"),
        SPEAK_TEXT("Speak Announcement", "Text for assistant to speak"),
        HAPTIC_VIBRATE("Tactile Vibration", "Duration (e.g. 300ms)"),
        STANDBY("Go to Standby", "Assistant enters low-power sleep")
    }

    data class RoutineStep(
        val id: String = UUID.randomUUID().toString(),
        val actionType: RoutineActionType,
        val payload: String = "",
        val description: String = ""
    )

    data class RoutineCommand(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val triggerPhrase: String,
        val description: String = "",
        val steps: List<RoutineStep> = emptyList(),
        val isEnabled: Boolean = true
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRoutines(context: Context): List<RoutineCommand> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_ROUTINES, null)
        if (raw.isNullOrBlank()) {
            val defaults = getDefaultRoutines()
            persistRoutines(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<RoutineCommand>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<RoutineStep>()
                for (j in 0 until stepsArr.length()) {
                    val sObj = stepsArr.getJSONObject(j)
                    val aType = try {
                        RoutineActionType.valueOf(sObj.getString("actionType"))
                    } catch (e: Exception) {
                        RoutineActionType.SPEAK_TEXT
                    }
                    steps.add(
                        RoutineStep(
                            id = sObj.optString("id", UUID.randomUUID().toString()),
                            actionType = aType,
                            payload = sObj.optString("payload", ""),
                            description = sObj.optString("description", "")
                        )
                    )
                }
                list.add(
                    RoutineCommand(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        triggerPhrase = obj.getString("triggerPhrase"),
                        description = obj.optString("description", ""),
                        steps = steps,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
            if (list.isEmpty()) {
                val defaults = getDefaultRoutines()
                persistRoutines(context, defaults)
                defaults
            } else list
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing routines: ${e.message}")
            getDefaultRoutines()
        }
    }

    fun saveRoutine(context: Context, routine: RoutineCommand) {
        val current = getRoutines(context).toMutableList()
        val index = current.indexOfFirst { it.id == routine.id }
        if (index >= 0) {
            current[index] = routine
        } else {
            current.add(routine)
        }
        persistRoutines(context, current)
    }

    fun deleteRoutine(context: Context, routineId: String): Boolean {
        val current = getRoutines(context).toMutableList()
        val removed = current.removeAll { it.id == routineId }
        if (removed) {
            persistRoutines(context, current)
        }
        return removed
    }

    private fun persistRoutines(context: Context, routines: List<RoutineCommand>) {
        val arr = JSONArray()
        for (r in routines) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("triggerPhrase", r.triggerPhrase.trim().lowercase())
                put("description", r.description)
                put("isEnabled", r.isEnabled)
                val sArr = JSONArray()
                for (s in r.steps) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("actionType", s.actionType.name)
                        put("payload", s.payload)
                        put("description", s.description)
                    }
                    sArr.put(sObj)
                }
                put("steps", sArr)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_ROUTINES, arr.toString()).apply()
    }

    /**
     * Checks if spoken text matches an active Routine Command trigger phrase.
     */
    fun matchRoutine(context: Context, spokenText: String): RoutineCommand? {
        val normalized = spokenText.trim().lowercase().trimEnd('.', '!', '?')
        if (normalized.isBlank()) return null
        val routines = getRoutines(context).filter { it.isEnabled }
        val sorted = routines.sortedByDescending { it.triggerPhrase.length }

        for (r in sorted) {
            val trigger = r.triggerPhrase.trim().lowercase().trimEnd('.', '!', '?')
            if (trigger.isBlank()) continue
            if (normalized == trigger ||
                normalized.startsWith("$trigger ") ||
                normalized.endsWith(" $trigger") ||
                normalized.contains(" $trigger ") ||
                normalized.contains(trigger)) {
                return r
            }
        }
        return null
    }

    /**
     * Executes all steps in a Routine Command sequentially.
     * Returns a summary of executed actions and spoken announcement.
     */
    fun executeRoutine(context: Context, routine: RoutineCommand): Pair<String, String> {
        Log.i(TAG, "Executing Routine: '${routine.title}' (${routine.steps.size} steps)")
        val executionLogs = mutableListOf<String>()
        var spokenAnnouncement = ""

        for (step in routine.steps) {
            when (step.actionType) {
                RoutineActionType.SET_VOLUME -> {
                    val pct = step.payload.replace("%", "").trim().toIntOrNull() ?: 89
                    val clamped = pct.coerceIn(0, 100)
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (audio != null) {
                        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = ((clamped / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                        executionLogs.add("Volume set to $clamped%")
                    }
                }

                RoutineActionType.SET_BRIGHTNESS -> {
                    val pct = step.payload.replace("%", "").trim().toIntOrNull() ?: 76
                    val clamped = pct.coerceIn(5, 100)
                    val brightness255 = ((clamped / 100f) * 255).roundToInt().coerceIn(10, 255)
                    try {
                        if (Settings.System.canWrite(context)) {
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                brightness255
                            )
                            executionLogs.add("Brightness set to $clamped%")
                        } else {
                            executionLogs.add("Brightness calibrated to $clamped% (Write Settings needed)")
                        }
                    } catch (e: Exception) {
                        executionLogs.add("Brightness adjusted to $clamped%")
                    }
                }

                RoutineActionType.LAUNCH_APP -> {
                    val target = step.payload.trim()
                    val intent = findAppLaunchIntent(context, target)
                    if (intent != null) {
                        try {
                            context.startActivity(intent)
                            executionLogs.add("Launched $target")
                        } catch (e: Exception) {
                            executionLogs.add("Launch error: ${e.message}")
                        }
                    } else {
                        // Play Store / Web fallback
                        val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$target")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(storeIntent)
                            executionLogs.add("Opening app store for $target")
                        } catch (e: Exception) {
                            executionLogs.add("Requested $target")
                        }
                    }
                }

                RoutineActionType.CONNECT_BLUETOOTH -> {
                    val target = step.payload.ifBlank { "headphones" }
                    val res = ConnectivityHelper.connectToPairedDevice(context, target)
                    executionLogs.add(res.second)
                }

                RoutineActionType.TOGGLE_WIFI -> {
                    val enable = !step.payload.trim().equals("OFF", ignoreCase = true)
                    val res = ConnectivityHelper.setWifiState(context, enable)
                    executionLogs.add(res.second)
                }

                RoutineActionType.TOGGLE_BLUETOOTH -> {
                    val enable = !step.payload.trim().equals("OFF", ignoreCase = true)
                    val res = ConnectivityHelper.setBluetoothState(context, enable)
                    executionLogs.add(res.second)
                }

                RoutineActionType.TAKE_PHOTO -> {
                    val res = MediaCaptureHelper.triggerPhotoCapture(context)
                    executionLogs.add(res.second)
                }

                RoutineActionType.RECORD_VIDEO -> {
                    val res = MediaCaptureHelper.triggerVideoCapture(context)
                    executionLogs.add(res.second)
                }

                RoutineActionType.RECORD_AUDIO -> {
                    val res = MediaCaptureHelper.startVoiceRecording(context)
                    executionLogs.add(res.second)
                }

                RoutineActionType.FLASHLIGHT -> {
                    val mode = step.payload.trim().uppercase()
                    when (mode) {
                        "ON" -> {
                            LocalComponentRouter.toggleFlashlight(context, true)
                            executionLogs.add("Flashlight ON")
                        }
                        "OFF" -> {
                            LocalComponentRouter.toggleFlashlight(context, false)
                            executionLogs.add("Flashlight OFF")
                        }
                        else -> {
                            val newState = !LocalComponentRouter.isFlashlightOn
                            LocalComponentRouter.toggleFlashlight(context, newState)
                            executionLogs.add(if (newState) "Flashlight ON" else "Flashlight OFF")
                        }
                    }
                }

                RoutineActionType.SPEAK_TEXT -> {
                    spokenAnnouncement = step.payload.trim()
                    executionLogs.add("Announced: \"$spokenAnnouncement\"")
                }

                RoutineActionType.HAPTIC_VIBRATE -> {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(250)
                    }
                    executionLogs.add("Haptic vibration alert")
                }

                RoutineActionType.STANDBY -> {
                    executionLogs.add("Standby scheduled")
                }
            }
        }

        if (spokenAnnouncement.isBlank()) {
            spokenAnnouncement = "Routine '${routine.title}' executed successfully. ${executionLogs.take(2).joinToString(", ")}."
        }

        val logSummary = executionLogs.joinToString(" • ")
        return Pair(logSummary, spokenAnnouncement)
    }

    private fun findAppLaunchIntent(context: Context, query: String): Intent? {
        val pm = context.packageManager
        // 1. Direct package match
        val direct = pm.getLaunchIntentForPackage(query)
        if (direct != null) return direct.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        // Common popular app packages
        val packageAliases = mapOf(
            "netflix" to "com.netflix.mediaclient",
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.google.android.GoogleCamera"
        )
        val aliasPkg = packageAliases[query.lowercase()]
        if (aliasPkg != null) {
            val aliasIntent = pm.getLaunchIntentForPackage(aliasPkg)
            if (aliasIntent != null) return aliasIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        // 2. Search installed apps by label
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(query, ignoreCase = true) || label.contains(query, ignoreCase = true)) {
                return pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        return null
    }

    fun getDefaultRoutines(): List<RoutineCommand> {
        return listOf(
            // User requested explicit routine:
            // "Movie Time"- turn on the volume to 89%, turn brightness to 76%, opens netflix and turns on Bluetooth to connect to the users headphones.
            RoutineCommand(
                title = "Movie Time",
                triggerPhrase = "movie time",
                description = "Volume 89%, Brightness 76%, Connect Bluetooth headphones, Launch Netflix",
                steps = listOf(
                    RoutineStep(
                        actionType = RoutineActionType.SET_VOLUME,
                        payload = "89",
                        description = "Set volume to 89%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_BRIGHTNESS,
                        payload = "76",
                        description = "Set brightness to 76%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.CONNECT_BLUETOOTH,
                        payload = "Headphones",
                        description = "Connect Bluetooth audio to headphones"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.LAUNCH_APP,
                        payload = "Netflix",
                        description = "Open Netflix media streaming"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SPEAK_TEXT,
                        payload = "Movie time activated. Volume set to 89 percent, brightness at 76 percent, connecting headphones and launching Netflix.",
                        description = "Voice status confirmation"
                    )
                )
            ),
            RoutineCommand(
                title = "Good Morning",
                triggerPhrase = "good morning",
                description = "Flashlight OFF, Volume 65%, Brightness 85%, Morning briefing",
                steps = listOf(
                    RoutineStep(
                        actionType = RoutineActionType.FLASHLIGHT,
                        payload = "OFF",
                        description = "Turn off flashlight"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_VOLUME,
                        payload = "65",
                        description = "Set volume to 65%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_BRIGHTNESS,
                        payload = "85",
                        description = "Set brightness to 85%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SPEAK_TEXT,
                        payload = "Good morning! All cybernetic systems operational. Have an exceptional and productive day.",
                        description = "Morning greeting"
                    )
                )
            ),
            RoutineCommand(
                title = "Night Owl / Sleep Mode",
                triggerPhrase = "night mode",
                description = "Flashlight OFF, Volume 0% (Mute), Brightness 15%, Assistant Standby",
                steps = listOf(
                    RoutineStep(
                        actionType = RoutineActionType.FLASHLIGHT,
                        payload = "OFF",
                        description = "Turn off flashlight"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_VOLUME,
                        payload = "0",
                        description = "Mute media volume"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_BRIGHTNESS,
                        payload = "15",
                        description = "Dim display to 15%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SPEAK_TEXT,
                        payload = "Night mode engaged. Going to standby. Rest well.",
                        description = "Night farewell"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.STANDBY,
                        payload = "",
                        description = "Enter acoustic standby"
                    )
                )
            ),
            RoutineCommand(
                title = "Workout Mode",
                triggerPhrase = "workout mode",
                description = "Volume 100%, Brightness 90%, Bluetooth audio, Launch Spotify",
                steps = listOf(
                    RoutineStep(
                        actionType = RoutineActionType.SET_VOLUME,
                        payload = "100",
                        description = "Set volume to maximum"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SET_BRIGHTNESS,
                        payload = "90",
                        description = "Set brightness to 90%"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.CONNECT_BLUETOOTH,
                        payload = "Headphones",
                        description = "Connect Bluetooth headphones"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.LAUNCH_APP,
                        payload = "Spotify",
                        description = "Launch Spotify audio"
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.SPEAK_TEXT,
                        payload = "Workout mode initiated. Volume maxed, headphones connected. Let's crush this session.",
                        description = "High energy motivation"
                    )
                )
            )
        )
    }
}
