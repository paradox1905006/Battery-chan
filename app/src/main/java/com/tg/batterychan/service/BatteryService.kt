package com.tg.batterychan.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.tg.batterychan.R
import com.tg.batterychan.data.BatteryEvent
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.metrics.Battery
import com.tg.batterychan.metrics.BatterySnapshot
import com.tg.batterychan.metrics.PeriodicTask
import com.tg.batterychan.metrics.fmt
import com.tg.batterychan.metrics.fmtSeconds
import com.tg.batterychan.receiver.BootReceiver
import com.tg.batterychan.ui.MainActivity
import com.tg.batterychan.ui.PopUpActivity
import java.io.File

class BatteryService : Service() {

    companion object {
        // Sent by SettingsActivity whenever the power-indicator prefs change, so the
        // running service can pick up new units/scalar/invert settings immediately.
        const val ACTION_METRICS_SETTINGS_CHANGED = "com.tg.batterychan.METRICS_SETTINGS_CHANGED"

        private const val METRICS_INTERVAL_MS = 1_250L
    }

    private lateinit var prefs: Prefs
    private var lastLevel = -1
    private var lastStatus = -1
    private val CHANNEL_ID = "battery_events_channel"
    private val NOTIFICATION_ID = 1
    private val METRICS_CHANNEL_ID = "power_status_channel"
    private val METRICS_NOTIFICATION_ID = 2

    // --- Ported detailed-metrics engine (from Wattz) ---
    private lateinit var battery: Battery
    private var metricsSnapshot: BatterySnapshot? = null
    private val metricsTask = PeriodicTask({ updateMetrics() }, METRICS_INTERVAL_MS)
    private lateinit var metricsNotificationBuilder: NotificationCompat.Builder

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> metricsTask.stop()
                Intent.ACTION_SCREEN_ON -> if (prefs.isPowerIndicatorEnabled()) metricsTask.start()
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChange(intent)
                Intent.ACTION_POWER_CONNECTED -> {
                    triggerEvent(BatteryEvent.CHARGING_CONNECTED)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (lastLevel < 100) {
                        triggerEvent(BatteryEvent.CHARGER_UNPLUGGED)
                    }
                }
                ACTION_METRICS_SETTINGS_CHANGED -> {
                    loadMetricsPrefs()
                    if (prefs.isPowerIndicatorEnabled()) {
                        metricsTask.start()
                        updateMetrics()
                    } else {
                        metricsTask.stop()
                        val notificationManager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(METRICS_NOTIFICATION_ID)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
        createMetricsNotificationChannel()

        battery = Battery(applicationContext)
        loadMetricsPrefs()

        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.let {
            lastLevel = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            lastStatus = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        }

        val notification = createForegroundNotification(if (lastLevel != -1) lastLevel else 0, 
            lastStatus == BatteryManager.BATTERY_STATUS_CHARGING || lastStatus == BatteryManager.BATTERY_STATUS_FULL)
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        startForeground(NOTIFICATION_ID, notification, type)
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_METRICS_SETTINGS_CHANGED)
        }
        
        ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Only run the metrics loop if the optional live power indicator is enabled -
        // the in-app detailed stats card computes its own snapshot independently in
        // MainActivity, so this ticker isn't needed just for that.
        if (prefs.isPowerIndicatorEnabled()) {
            metricsTask.start()
        }
    }

    private fun loadMetricsPrefs() {
        battery.currentScalar = prefs.getCurrentScalar().toDouble()
        battery.invertCurrent = prefs.isInvertCurrent()
    }

    private fun handleBatteryChange(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        if (level != lastLevel || status != lastStatus) {
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            // Low Battery (<= 20%)
            if (level <= 20 && lastLevel > 20) {
                triggerEvent(BatteryEvent.LOW_BATTERY)
            }

            // Full Charge (100%)
            if (level == 100 && lastLevel < 100 && isCharging) {
                triggerEvent(BatteryEvent.FULL_CHARGE)
            }

            updateForegroundNotification(level, isCharging)
            lastLevel = level
            lastStatus = status
        }
    }

    private fun triggerEvent(event: BatteryEvent) {
        try {
            if (!prefs.isGlobalEventsEnabled()) return

            val audioPath = prefs.getFilePath(event, Prefs.FileType.AUDIO)
            val imagePath = prefs.getFilePath(event, Prefs.FileType.IMAGE)
            val customText = prefs.getEventText(event)

            showEventNotification(event, imagePath)
            popUpEvent(event, imagePath, audioPath, customText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun popUpEvent(event: BatteryEvent, imagePath: String?, audioPath: String?, customText: String?) {
        val intent = Intent(this, PopUpActivity::class.java).apply {
            putExtra("event_title", event.displayName)
            putExtra("image_path", imagePath)
            putExtra("audio_path", audioPath)
            putExtra("custom_text", customText)
            putExtra("duration", prefs.getNotificationDuration())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun showEventNotification(event: BatteryEvent, imagePath: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery_alert)
            .setContentTitle("Battery Event")
            .setContentText(event.displayName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        if (imagePath != null && File(imagePath).exists()) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
        }

        notificationManager.notify(event.ordinal + 10, builder.build())
    }

    private fun createForegroundNotification(level: Int, isCharging: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val statusText = if (isCharging) "Charging" else "Not Charging"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Chan Monitor")
            .setContentText("Battery: $level% ($statusText)")
            .setSmallIcon(R.drawable.ic_battery_full) // Need to create this
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(level: Int, isCharging: Boolean) {
        val notification = createForegroundNotification(level, isCharging)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // Refreshes the detailed snapshot and, since the user has opted in, updates the live
    // status bar power indicator. Ported/adapted from Wattz's StatusService.update().
    private fun updateMetrics() {
        val snapshot = battery.snapshot()
        metricsSnapshot = snapshot
        updatePowerIndicatorNotification(snapshot)
    }

    private fun updatePowerIndicatorNotification(snapshot: BatterySnapshot) {
        val units = prefs.getIndicatorUnits()
        val value = when (units) {
            "Ah" -> snapshot.energyAmpHours
            "C" -> snapshot.celsius
            "V" -> snapshot.volts
            "Wh" -> snapshot.energyWattHours
            "%" -> snapshot.levelPercent
            else -> snapshot.volts // Default to Voltage if units was Power/Current
        }
        val label = when (units) {
            "Ah", "Wh" -> "Energy"
            "C" -> "Temperature"
            "V" -> "Voltage"
            "%" -> "Charge"
            else -> "Voltage"
        }
        val displayUnits = when (units) {
            "C" -> "\u00B0C"
            "V" -> "V"
            "Ah" -> "Ah"
            "Wh" -> "Wh"
            "%" -> "%"
            else -> "V"
        }
        val txtValue = fmt(value)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        metricsNotificationBuilder = NotificationCompat.Builder(this, METRICS_CHANNEL_ID)
            .setContentTitle("Battery $label: $txtValue$displayUnits")
            .setContentText(
                when (val seconds = snapshot.secondsUntilCharged) {
                    null -> ""
                    0.0 -> "fully charged"
                    else -> "${fmtSeconds(seconds)} until full charge"
                }
            )
            .setSmallIcon(renderIcon(txtValue, displayUnits))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        notificationManager.notify(METRICS_NOTIFICATION_ID, metricsNotificationBuilder.build())
    }

    // Renders the live numeric value directly into the status bar icon. Ported from
    // Wattz's StatusService.renderIcon().
    private fun renderIcon(value: String, unit: String): IconCompat {
        val density = resources.displayMetrics.density
        val w = (48f * density).toInt()
        val bitmap = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            textSize = 28f * density
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(value, w / 2f, w / 2f, paint)
        canvas.drawText(unit, w / 2f, w.toFloat(), paint)

        return IconCompat.createWithBitmap(bitmap)
    }

    private fun createMetricsNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                METRICS_CHANNEL_ID,
                "Power Status",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Optional live status bar indicator showing real-time battery power draw"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Events",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Channel for battery event notifications"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // This is called when the user swipes the app away from recents
        // We try to keep the service running by sending a broadcast to restart it if necessary
        if (prefs.isServiceEnabled()) {
            val restartServiceIntent = Intent(applicationContext, this.javaClass)
            restartServiceIntent.setPackage(packageName)
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent, 
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (prefs.isServiceEnabled()) {
            // If the service is destroyed but it's still supposed to be enabled, try to restart it
            val broadcastIntent = Intent(this, BootReceiver::class.java)
            sendBroadcast(broadcastIntent)
        }
        metricsTask.stop()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {}
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(METRICS_NOTIFICATION_ID)
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
