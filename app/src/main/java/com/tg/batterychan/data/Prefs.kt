package com.tg.batterychan.data

import android.content.Context
import android.content.SharedPreferences
import com.tg.batterychan.util.Media
import com.tg.batterychan.util.MediaKind
import org.json.JSONArray
import java.io.File

class Prefs(context: Context) {
    // Name kept as-is so existing users keep their saved events and files.
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("oni_chan_prefs", Context.MODE_PRIVATE)

    // --- Per-event data. Keyed by string so built-in and custom events share one code path. ---

    fun setFilePath(key: String, type: FileType, path: String?) {
        prefs.edit().putString("${key}_${type.tag}", path).apply()
    }

    fun getFilePath(key: String, type: FileType): String? =
        prefs.getString("${key}_${type.tag}", null)

    fun setEventText(key: String, text: String?) {
        prefs.edit().putString("${key}_text", text).apply()
    }

    fun getEventText(key: String): String? = prefs.getString("${key}_text", null)

    fun setEventEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("${key}_enabled", enabled).apply()
    }

    fun isEventEnabled(key: String): Boolean = prefs.getBoolean("${key}_enabled", true)

    /**
     * How an event's picture fills the pop-up: [SCALE_FILL] (crop), [SCALE_STRETCH] or [SCALE_FIT].
     * Until the user picks one, images / GIFs fill and videos stretch (how it always worked).
     */
    fun getEventScale(key: String, mediaPath: String?): Int {
        val saved = prefs.getInt("${key}_scale", -1)
        if (saved in SCALE_FILL..SCALE_FIT) return saved
        return if (mediaPath != null && Media.kindOf(mediaPath) == MediaKind.VIDEO) SCALE_STRETCH else SCALE_FILL
    }

    fun setEventScale(key: String, mode: Int) {
        prefs.edit().putInt("${key}_scale", mode.coerceIn(SCALE_FILL, SCALE_FIT)).apply()
    }

    /** Removes every stored value for an event (used when a custom event is deleted). */
    fun clearEvent(key: String) {
        prefs.edit()
            .remove("${key}_${FileType.AUDIO.tag}")
            .remove("${key}_${FileType.IMAGE.tag}")
            .remove("${key}_text")
            .remove("${key}_enabled")
            .remove("${key}_scale")
            .apply()
    }

    // --- Custom events ---

    fun getCustomEvents(): List<CustomEvent> =
        CustomEvent.listFromJson(prefs.getString("custom_events", null))

    /** Inserts a new event or replaces the one with the same id. */
    fun saveCustomEvent(event: CustomEvent) {
        val list = getCustomEvents().toMutableList()
        val index = list.indexOfFirst { it.id == event.id }
        if (index >= 0) list[index] = event else list.add(event)
        prefs.edit().putString("custom_events", CustomEvent.listToJson(list)).apply()
    }

    fun deleteCustomEvent(id: String) {
        val list = getCustomEvents().filterNot { it.id == id }
        prefs.edit().putString("custom_events", CustomEvent.listToJson(list)).apply()
    }

    // --- Service / general ---

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("service_enabled", enabled).apply()
    }

    fun isServiceEnabled(): Boolean = prefs.getBoolean("service_enabled", false)

    fun setGlobalEventsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("global_events_enabled", enabled).apply()
    }

    fun isGlobalEventsEnabled(): Boolean = prefs.getBoolean("global_events_enabled", true)

    fun setNotificationDuration(seconds: Int) {
        prefs.edit().putInt("notification_duration", seconds).apply()
    }

    fun getNotificationDuration(): Int = prefs.getInt("notification_duration", 10)

    fun setAutoDismissOnAudio(enabled: Boolean) {
        prefs.edit().putBoolean("auto_dismiss_audio", enabled).apply()
    }

    fun isAutoDismissOnAudio(): Boolean = prefs.getBoolean("auto_dismiss_audio", false)

    /** Raise media volume to max while an event sound plays (previous behaviour, now optional). */
    fun setBoostVolume(enabled: Boolean) {
        prefs.edit().putBoolean("boost_volume", enabled).apply()
    }

    fun isBoostVolume(): Boolean = prefs.getBoolean("boost_volume", true)

    // --- Appearance ---

    fun getThemeMode(): Int = prefs.getInt("theme_mode", THEME_SYSTEM)

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt("theme_mode", mode).apply()
    }

    fun isDynamicColor(): Boolean = prefs.getBoolean("dynamic_color", true)

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    /** Changes whenever something that affects the activity theme changes. */
    fun themeSignature(): String = "${getThemeMode()}_${isDynamicColor()}"

    // --- Detailed metrics / power indicator (ported from Wattz) ---

    fun isPowerIndicatorEnabled(): Boolean = prefs.getBoolean("power_indicator_enabled", false)

    fun setPowerIndicatorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("power_indicator_enabled", enabled).apply()
    }

    // One of "A", "Ah", "C", "V", "Wh", "%", or null/"W" for watts (default).
    fun getIndicatorUnits(): String? = prefs.getString("indicator_units", null)

    fun setIndicatorUnits(units: String?) {
        prefs.edit().putString("indicator_units", units).apply()
    }

    // Power scalar workaround: 1000x, 1x (default), or 0.001x.
    fun getCurrentScalar(): Float = prefs.getFloat("current_scalar", 1f)

    fun setCurrentScalar(scalar: Float) {
        prefs.edit().putFloat("current_scalar", scalar).apply()
    }

    fun isInvertCurrent(): Boolean = prefs.getBoolean("invert_current", false)

    fun setInvertCurrent(enabled: Boolean) {
        prefs.edit().putBoolean("invert_current", enabled).apply()
    }

    // --- Mini player (floating looping video / GIF / image on the home screen) ---

    fun isMiniEnabled(): Boolean = prefs.getBoolean("mini_enabled", false)

    fun setMiniEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mini_enabled", enabled).apply()
    }

    /**
     * Locked = cannot be moved or resized, and touches pass through to what is underneath.
     * The home screen and the lock screen ([lock] = true) are locked separately, so the player
     * can stay fixed on the home screen and still be edited on the lock screen.
     */
    fun isMiniLocked(lock: Boolean = false): Boolean =
        prefs.getBoolean(if (lock) "mini_lock_locked" else "mini_locked", false)

    fun setMiniLocked(locked: Boolean, lock: Boolean = false) {
        prefs.edit().putBoolean(if (lock) "mini_lock_locked" else "mini_locked", locked).apply()
    }

    fun isMiniHomeOnly(): Boolean = prefs.getBoolean("mini_home_only", true)

    fun setMiniHomeOnly(enabled: Boolean) {
        prefs.edit().putBoolean("mini_home_only", enabled).apply()
    }

    /** Hide the mini player while the launcher's app drawer is open (needs the accessibility service). */
    fun isMiniHideDrawer(): Boolean = prefs.getBoolean("mini_hide_drawer", false)

    fun setMiniHideDrawer(enabled: Boolean) {
        prefs.edit().putBoolean("mini_hide_drawer", enabled).apply()
    }

    /** Also show on the lock screen (done with an accessibility overlay, so it needs that service). */
    fun isMiniShowOnLock(): Boolean = prefs.getBoolean("mini_show_on_lock", false)

    fun setMiniShowOnLock(enabled: Boolean) {
        prefs.edit().putBoolean("mini_show_on_lock", enabled).apply()
    }

    /** 20..100 (%). */
    fun getMiniOpacity(): Int = prefs.getInt("mini_opacity", 100)

    fun setMiniOpacity(percent: Int) {
        prefs.edit().putInt("mini_opacity", percent.coerceIn(20, 100)).apply()
    }

    /** 0 = square corners, 100 = fully round ends (a circle for a square player). */
    fun getMiniCorner(): Int = prefs.getInt("mini_corner", 18)

    fun setMiniCorner(percent: Int) {
        prefs.edit().putInt("mini_corner", percent.coerceIn(0, 100)).apply()
    }

    /** Freeze the picture while Android's battery saver is on. */
    fun isMiniPauseInSaver(): Boolean = prefs.getBoolean("mini_pause_saver", true)

    fun setMiniPauseInSaver(enabled: Boolean) {
        prefs.edit().putBoolean("mini_pause_saver", enabled).apply()
    }

    /** Text that only shows in the app drawer (e.g. its search box hint). Helps drawer detection. */
    fun getMiniDrawerMarker(): String = prefs.getString("mini_drawer_marker", "Enter an app name") ?: ""

    fun setMiniDrawerMarker(text: String) {
        prefs.edit().putString("mini_drawer_marker", text).apply()
    }

    /** One of [MINI_SCALE_CROP], [MINI_SCALE_STRETCH], [MINI_SCALE_FIT]. */
    fun getMiniScaleMode(): Int = prefs.getInt("mini_scale_mode", MINI_SCALE_CROP)

    fun setMiniScaleMode(mode: Int) {
        prefs.edit().putInt("mini_scale_mode", mode).apply()
    }

    private fun geoKey(lock: Boolean, name: String) = if (lock) "mini_lock_$name" else "mini_$name"

    /**
     * Position in px (top-left of the window). -1 means "not placed yet". The lock screen has its
     * own position and size ([lock] = true); until it is set it follows the home screen one.
     */
    fun getMiniX(lock: Boolean = false): Int = prefs.getInt(geoKey(lock, "x"), -1)

    fun getMiniY(lock: Boolean = false): Int = prefs.getInt(geoKey(lock, "y"), -1)

    /** Size in px. 0 means "not sized yet", the service picks a size from the media's aspect ratio. */
    fun getMiniWidth(lock: Boolean = false): Int = prefs.getInt(geoKey(lock, "w"), 0)

    fun getMiniHeight(lock: Boolean = false): Int = prefs.getInt(geoKey(lock, "h"), 0)

    fun setMiniGeometry(x: Int, y: Int, w: Int, h: Int, lock: Boolean = false) {
        prefs.edit()
            .putInt(geoKey(lock, "x"), x).putInt(geoKey(lock, "y"), y)
            .putInt(geoKey(lock, "w"), w).putInt(geoKey(lock, "h"), h)
            .apply()
    }

    /** Forgets the position and size of both the home screen and the lock screen. */
    fun resetMiniGeometry() {
        setMiniGeometry(-1, -1, 0, 0, lock = false)
        setMiniGeometry(-1, -1, 0, 0, lock = true)
    }

    /** Keeps the positions, lets the service pick a fresh size (used when new media is picked). */
    fun resetMiniSize() {
        setMiniGeometry(getMiniX(false), getMiniY(false), 0, 0, lock = false)
        setMiniGeometry(getMiniX(true), getMiniY(true), 0, 0, lock = true)
    }

    // --- Network speed meter ---

    /** Show download / upload speed in bold under the mini player. */
    fun isSpeedUnderPlayer(): Boolean = prefs.getBoolean("speed_under_player", false)

    fun setSpeedUnderPlayer(enabled: Boolean) {
        prefs.edit().putBoolean("speed_under_player", enabled).apply()
    }

    /** Text colour of the speed meter (ARGB). */
    fun getSpeedColor(): Int = prefs.getInt("speed_color", 0xFFFFFFFF.toInt())

    fun setSpeedColor(color: Int) {
        prefs.edit().putInt("speed_color", color).apply()
    }

    /** When on (default) the speed text inside the player takes its colour from the video / picture behind it. */
    fun isSpeedColorAuto(): Boolean = prefs.getBoolean("speed_color_auto", true)

    fun setSpeedColorAuto(enabled: Boolean) {
        prefs.edit().putBoolean("speed_color_auto", enabled).apply()
    }

    /** Text size of the speed meter in sp (it still shrinks if the player is too narrow). */
    fun getSpeedSize(): Int = prefs.getInt("speed_size", 12)

    fun setSpeedSize(sp: Int) {
        prefs.edit().putInt("speed_size", sp.coerceIn(8, 40)).apply()
    }

    /**
     * Where the speed meter sits inside the player: 0 = left / top edge, 1 = right / bottom edge.
     * Default is bottom centre.
     */
    fun getSpeedPosX(): Float = prefs.getFloat("speed_pos_x", 0.5f)

    fun getSpeedPosY(): Float = prefs.getFloat("speed_pos_y", 0.92f)

    fun setSpeedPos(x: Float, y: Float) {
        prefs.edit().putFloat("speed_pos_x", x.coerceIn(0f, 1f)).putFloat("speed_pos_y", y.coerceIn(0f, 1f)).apply()
    }

    /** Show download / upload speed in the notification panel. */
    fun isSpeedInNotification(): Boolean = prefs.getBoolean("speed_in_notification", false)

    fun setSpeedInNotification(enabled: Boolean) {
        prefs.edit().putBoolean("speed_in_notification", enabled).apply()
    }

    // --- Mini player playlist ---

    /** All pictures / GIFs / videos of the mini player, in the order they were added. */
    fun getMiniPlaylist(): List<String> {
        val raw = prefs.getString("mini_playlist", null)
        val list = mutableListOf<String>()
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) list.add(array.getString(i))
            } catch (_: Exception) {
            }
        } else {
            // Media picked before playlists existed.
            getFilePath(MINI_KEY, FileType.IMAGE)?.let { list.add(it) }
        }
        return list.filter { File(it).exists() }
    }

    private fun saveMiniPlaylist(list: List<String>) {
        prefs.edit().putString("mini_playlist", JSONArray(list).toString()).apply()
    }

    fun addMiniItem(path: String) {
        val list = getMiniPlaylist().toMutableList()
        if (path !in list) list.add(path)
        saveMiniPlaylist(list)
    }

    fun removeMiniItem(path: String) {
        saveMiniPlaylist(getMiniPlaylist().filter { it != path })
    }

    /** The first item (used for the first-time size of the player). */
    fun getMiniMediaPath(): String? = getMiniPlaylist().firstOrNull()

    /** Pick the next item at random instead of in order. */
    fun isMiniShuffle(): Boolean = prefs.getBoolean("mini_shuffle", false)

    fun setMiniShuffle(enabled: Boolean) {
        prefs.edit().putBoolean("mini_shuffle", enabled).apply()
    }

    /** 0 = never change, -1 = every time the screen turns on, otherwise seconds. */
    fun getMiniSwitchSecs(): Int = prefs.getInt("mini_switch_secs", 0)

    fun setMiniSwitchSecs(secs: Int) {
        prefs.edit().putInt("mini_switch_secs", secs).apply()
    }

    /** Stick to the screen edges when dragged close to them. */
    fun isMiniSnap(): Boolean = prefs.getBoolean("mini_snap", false)

    fun setMiniSnap(enabled: Boolean) {
        prefs.edit().putBoolean("mini_snap", enabled).apply()
    }

    /** Freeze the picture when the battery is below this percent and not charging. 0 = off. */
    fun getMiniPauseBelow(): Int = prefs.getInt("mini_pause_below", 0)

    fun setMiniPauseBelow(percent: Int) {
        prefs.edit().putInt("mini_pause_below", percent.coerceIn(0, 50)).apply()
    }

    /** Show the speed in bits (Mbps) instead of bytes (MB/s). */
    fun isSpeedBits(): Boolean = prefs.getBoolean("speed_bits", false)

    fun setSpeedBits(enabled: Boolean) {
        prefs.edit().putBoolean("speed_bits", enabled).apply()
    }

    /** [tag] is part of the stored key. It is explicit so R8 renaming can never change it. */
    enum class FileType(val tag: String) {
        AUDIO("AUDIO"),
        IMAGE("IMAGE")
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_BLACK = 3

        /** Prefs/file key of the mini player's media (same storage as event media). */
        const val MINI_KEY = "mini_player"

        /** Picture fit options shared by events and the mini player. */
        const val SCALE_FILL = 0
        const val SCALE_STRETCH = 1
        const val SCALE_FIT = 2

        const val MINI_SCALE_CROP = 0
        const val MINI_SCALE_STRETCH = 1
        const val MINI_SCALE_FIT = 2
    }
}
