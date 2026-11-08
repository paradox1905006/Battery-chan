package com.tg.batterychan.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** When a custom event should fire relative to its battery level. */
enum class Trigger(val tag: String) {
    /** Fires when the level rises to (or past) the target while charging. */
    RISING("rising"),

    /** Fires when the level drops to (or below) the target. */
    FALLING("falling"),

    /** Fires when the level crosses the target in either direction. */
    BOTH("both");

    companion object {
        fun fromTag(tag: String?): Trigger = values().firstOrNull { it.tag == tag } ?: BOTH
    }
}

/**
 * A user-defined event, e.g. "at 80% while charging, show this image and play this sound".
 * Media/text for the event are stored in [Prefs] under [key], exactly like built-in events.
 */
data class CustomEvent(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val level: Int,
    val trigger: Trigger
) {
    /** Prefs/file key. Never collides with built-in keys. */
    val key: String get() = "custom_$id"

    val description: String
        get() = when (trigger) {
            Trigger.RISING -> "When charging reaches $level%"
            Trigger.FALLING -> "When battery drops to $level%"
            Trigger.BOTH -> "When battery crosses $level%"
        }

    /**
     * Crossing detection (not equality) so a level that is skipped, e.g. 79 -> 81, still
     * fires, and an unchanged level never fires twice.
     */
    fun matches(oldLevel: Int, newLevel: Int, isCharging: Boolean): Boolean {
        if (oldLevel < 0 || newLevel < 0 || oldLevel == newLevel) return false
        val rose = oldLevel < level && newLevel >= level
        val fell = oldLevel > level && newLevel <= level
        return when (trigger) {
            Trigger.RISING -> rose && isCharging
            Trigger.FALLING -> fell
            Trigger.BOTH -> rose || fell
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("level", level)
        .put("trigger", trigger.tag)

    companion object {
        fun fromJson(o: JSONObject) = CustomEvent(
            id = o.getString("id"),
            name = o.optString("name", "Custom event"),
            level = o.optInt("level", 50).coerceIn(1, 100),
            trigger = Trigger.fromTag(o.optString("trigger"))
        )

        fun listFromJson(json: String?): List<CustomEvent> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listToJson(events: List<CustomEvent>): String =
            JSONArray().also { arr -> events.forEach { arr.put(it.toJson()) } }.toString()
    }
}
