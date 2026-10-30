package com.example.tessaract

import android.content.Context
import android.content.SharedPreferences

/**
 * Project T.E.S.S.A.R.A.C.T. - Thermal & Battery Guardian Configuration
 * Controls on-device protective duty-cycling to prevent device overheating
 * and preserve battery life during continuous background acoustic monitoring.
 */
object GuardianConfig {
    private const val PREFS_NAME = "tessaract_guardian_prefs"
    private const val KEY_ENABLED = "guardian_protection_enabled"
    private const val KEY_BATTERY_THRESHOLD = "guardian_battery_threshold"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isProtectionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getBatteryThreshold(context: Context): Int {
        return getPrefs(context).getInt(KEY_BATTERY_THRESHOLD, 15)
    }

    fun setBatteryThreshold(context: Context, threshold: Int) {
        val clamped = threshold.coerceIn(5, 50)
        getPrefs(context).edit().putInt(KEY_BATTERY_THRESHOLD, clamped).apply()
    }
}
