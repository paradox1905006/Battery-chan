package com.tg.batterychan.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("oni_chan_prefs", Context.MODE_PRIVATE)

    fun setFilePath(event: BatteryEvent, type: FileType, path: String?) {
        prefs.edit().putString("${event.key}_${type.name}", path).apply()
    }

    fun getFilePath(event: BatteryEvent, type: FileType): String? {
        return prefs.getString("${event.key}_${type.name}", null)
    }

    fun setEventText(event: BatteryEvent, text: String?) {
        prefs.edit().putString("${event.key}_text", text).apply()
    }

    fun getEventText(event: BatteryEvent): String? {
        return prefs.getString("${event.key}_text", null)
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("service_enabled", enabled).apply()
    }

    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean("service_enabled", false)
    }

    fun setGlobalEventsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("global_events_enabled", enabled).apply()
    }

    fun isGlobalEventsEnabled(): Boolean {
        return prefs.getBoolean("global_events_enabled", true)
    }

    fun setNotificationDuration(seconds: Int) {
        prefs.edit().putInt("notification_duration", seconds).apply()
    }

    fun getNotificationDuration(): Int {
        return prefs.getInt("notification_duration", 10)
    }

    fun setAutoDismissOnAudio(enabled: Boolean) {
        prefs.edit().putBoolean("auto_dismiss_audio", enabled).apply()
    }

    fun isAutoDismissOnAudio(): Boolean {
        return prefs.getBoolean("auto_dismiss_audio", false)
    }

    // --- Detailed metrics / power indicator (ported from Wattz) ---

    fun isPowerIndicatorEnabled(): Boolean {
        return prefs.getBoolean("power_indicator_enabled", false)
    }

    fun setPowerIndicatorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("power_indicator_enabled", enabled).apply()
    }

    // One of "A", "Ah", "C", "V", "Wh", "%", or null/"W" for watts (default).
    fun getIndicatorUnits(): String? {
        return prefs.getString("indicator_units", null)
    }

    fun setIndicatorUnits(units: String?) {
        prefs.edit().putString("indicator_units", units).apply()
    }

    // Power scalar workaround: 1000x, 1x (default), or 0.001x.
    fun getCurrentScalar(): Float {
        return prefs.getFloat("current_scalar", 1f)
    }

    fun setCurrentScalar(scalar: Float) {
        prefs.edit().putFloat("current_scalar", scalar).apply()
    }

    fun isInvertCurrent(): Boolean {
        return prefs.getBoolean("invert_current", false)
    }

    fun setInvertCurrent(enabled: Boolean) {
        prefs.edit().putBoolean("invert_current", enabled).apply()
    }

    enum class FileType { AUDIO, IMAGE }
}
