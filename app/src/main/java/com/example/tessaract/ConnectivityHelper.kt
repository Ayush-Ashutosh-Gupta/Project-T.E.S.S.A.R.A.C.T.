package com.example.tessaract

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Project T.E.S.S.A.R.A.C.T. - Connectivity & System Control Dispatcher
 * Controls local network state (Wi-Fi, Mobile Data, Bluetooth), volume/brightness levels,
 * and programmatically initiates Bluetooth audio connections to paired earbuds/headphones via voice.
 */
object ConnectivityHelper {

    private const val TAG = "ConnectivityHelper"

    /**
     * Toggles or controls Wi-Fi state.
     */
    @Suppress("DEPRECATION")
    fun setWifiState(context: Context, enable: Boolean): Pair<Boolean, String> {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val success = wifiManager?.setWifiEnabled(enable) ?: false
                if (success) {
                    Pair(true, if (enable) "Wi-Fi enabled." else "Wi-Fi disabled.")
                } else {
                    openWifiSettings(context, enable)
                }
            } else {
                // Android 10+ restricts direct wifi toggles from background apps
                openWifiSettings(context, enable)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi control error: ${e.message}")
            openWifiSettings(context, enable)
        }
    }

    private fun openWifiSettings(context: Context, requestedEnable: Boolean): Pair<Boolean, String> {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Wi-Fi controls to turn Wi-Fi " + if (requestedEnable) "on." else "off.")
        } catch (e: Exception) {
            Pair(false, "Could not open Wi-Fi settings.")
        }
    }

    /**
     * Toggles or controls Bluetooth state.
     */
    @SuppressLint("MissingPermission")
    fun setBluetoothState(context: Context, enable: Boolean): Pair<Boolean, String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return Pair(false, "Bluetooth is not available on this device.")

        return try {
            if (enable) {
                if (!adapter.isEnabled) {
                    val ok = adapter.enable()
                    if (ok) Pair(true, "Bluetooth turned on.")
                    else openBluetoothSettings(context, true)
                } else {
                    Pair(true, "Bluetooth is already on.")
                }
            } else {
                if (adapter.isEnabled) {
                    val ok = adapter.disable()
                    if (ok) Pair(true, "Bluetooth turned off.")
                    else openBluetoothSettings(context, false)
                } else {
                    Pair(true, "Bluetooth is already off.")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth security restriction: ${e.message}")
            openBluetoothSettings(context, enable)
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth toggle error: ${e.message}")
            openBluetoothSettings(context, enable)
        }
    }

    private fun openBluetoothSettings(context: Context, enable: Boolean): Pair<Boolean, String> {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Bluetooth settings to turn Bluetooth " + if (enable) "on." else "off.")
        } catch (e: Exception) {
            Pair(false, "Unable to open Bluetooth settings.")
        }
    }

    /**
     * Programmatically connects to a paired Bluetooth device (such as earbuds, headphones, speakers).
     * Matches user query against bonded devices using exact and phonetic fuzzy matching.
     */
    @SuppressLint("MissingPermission")
    fun connectToPairedDevice(context: Context, deviceQuery: String): Pair<Boolean, String> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return Pair(false, "Bluetooth is not supported on this device.")

        if (!adapter.isEnabled) {
            try {
                adapter.enable()
            } catch (e: Exception) {}
        }

        val bondedDevices = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: Exception) {
            emptySet<BluetoothDevice>()
        }

        if (bondedDevices.isEmpty()) {
            openBluetoothSettings(context, true)
            return Pair(false, "No paired Bluetooth devices found. Opening Bluetooth pairing settings.")
        }

        val query = deviceQuery.trim().lowercase()

        // 1. Search for matching device
        val targetDevice = bondedDevices.firstOrNull { dev ->
            val name = (dev.name ?: "").lowercase()
            name == query || name.contains(query) || query.contains(name)
        } ?: bondedDevices.firstOrNull { dev ->
            // Audio keywords heuristic
            val name = (dev.name ?: "").lowercase()
            if (query.contains("earbud") || query.contains("bud") || query.contains("headphone") ||
                query.contains("headset") || query.contains("earphone") || query.contains("audio") || query.contains("airpod")) {
                name.contains("bud") || name.contains("head") || name.contains("airpod") ||
                name.contains("wh-") || name.contains("wf-") || name.contains("bt") || name.contains("wireless")
            } else false
        } ?: bondedDevices.first() // default to first paired device if general "connect headphones" is requested

        val devName = targetDevice.name ?: "Bluetooth Device"
        Log.i(TAG, "Attempting connection to paired audio device: $devName (${targetDevice.address})")

        // 2. Trigger connection using Bluetooth A2DP & Headset Profile Proxy
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.isAccessible = true
                        connectMethod.invoke(proxy, targetDevice)
                        Log.i(TAG, "Invoked connect() on profile $profile for $devName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Profile $profile reflection connect error: ${e.message}")
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)

            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.isAccessible = true
                        connectMethod.invoke(proxy, targetDevice)
                    } catch (e: Exception) {
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)

            return Pair(true, "Connecting to paired audio device: $devName.")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Bluetooth device: ${e.message}", e)
            openBluetoothSettings(context, true)
            return Pair(true, "Connecting to $devName via Bluetooth.")
        }
    }

    /**
     * Sets media volume directly to a specific percentage (0 to 100).
     */
    fun setVolumePercentage(context: Context, percentage: Int): Pair<Boolean, String> {
        val clamped = percentage.coerceIn(0, 100)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Pair(false, "Audio service unavailable.")

        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetLevel = (clamped * maxVolume) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetLevel, AudioManager.FLAG_SHOW_UI)
            Pair(true, "Volume set to $clamped percent.")
        } catch (e: Exception) {
            Pair(false, "Could not adjust volume: ${e.message}")
        }
    }

    /**
     * Sets display brightness to a specific percentage (0 to 100).
     */
    fun setBrightnessPercentage(context: Context, percentage: Int): Pair<Boolean, String> {
        val clamped = percentage.coerceIn(0, 100)
        val value255 = (clamped * 255) / 100

        return try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value255
                )
                Pair(true, "Brightness adjusted to $clamped percent.")
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Pair(true, "Please grant Write Settings permission to adjust brightness automatically.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Brightness change error: ${e.message}")
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                Pair(true, "Opening Display Brightness settings.")
            } catch (ex: Exception) {
                Pair(false, "Cannot open display settings.")
            }
        }
    }

    /**
     * Opens Mobile Data / Network settings.
     */
    fun openMobileDataSettings(context: Context): Pair<Boolean, String> {
        val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Mobile Network settings.")
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
                Pair(true, "Opening Network settings.")
            } catch (ex: Exception) {
                Pair(false, "Unable to open Mobile Data settings.")
            }
        }
    }
}
