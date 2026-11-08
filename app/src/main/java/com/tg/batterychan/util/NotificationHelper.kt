package com.tg.batterychan.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.tg.batterychan.R
import java.io.File

object NotificationHelper {
    const val CHANNEL_MONITOR = "battery_monitor_channel"
    const val CHANNEL_EVENTS = "battery_events_silent"
    const val CHANNEL_POWER = "power_status_channel"
    const val CHANNEL_MINI = "mini_player_channel"
    const val CHANNEL_SPEED = "speed_status_channel"

    // Old high-importance channel. It played the default notification sound on top of the
    // event audio (a second source of doubled sound), and a channel's sound cannot be
    // changed after creation, so it is replaced by CHANNEL_EVENTS.
    private const val LEGACY_EVENTS_CHANNEL = "battery_events_channel"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        try {
            nm.deleteNotificationChannel(LEGACY_EVENTS_CHANNEL)
        } catch (_: Exception) {
        }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while the battery monitor is running"
                setShowBadge(false)
            }
        )
        // Heads-up, but silent: the pop-up plays the event's own sound.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Battery Events", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a battery event happens"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_POWER, "Power Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Optional live status bar indicator showing real-time battery readings"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SPEED, "Speed meter", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live download / upload speed in the status bar"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MINI, "Mini player", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while the home screen mini player is running"
                setShowBadge(false)
            }
        )
    }

    fun showEvent(context: Context, key: String, title: String, text: String?, imagePath: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_battery_alert)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setAutoCancel(true)

        if (!text.isNullOrBlank()) builder.setContentText(text)

        if (imagePath != null && File(imagePath).exists()) {
            ImageLoader.decode(imagePath, 1024)?.let {
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it))
            }
        }
        nm.notify(key.hashCode(), builder.build())
    }
}
