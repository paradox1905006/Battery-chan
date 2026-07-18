package com.tg.batterychan.ui

import android.content.*
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tg.batterychan.R
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.databinding.ActivityMainBinding
import com.tg.batterychan.metrics.Battery
import com.tg.batterychan.metrics.PeriodicTask
import com.tg.batterychan.metrics.fmt
import com.tg.batterychan.metrics.fmtSeconds
import com.tg.batterychan.service.BatteryService
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    // --- Ported detailed-metrics engine (from Wattz) ---
    private lateinit var battery: Battery
    private var pluggedInAt: ZonedDateTime? = null
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val metricsTask = PeriodicTask({ refreshDetailedStats() }, 1_250L)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return

            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                pluggedInAt = ZonedDateTime.now()
            } else if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                pluggedInAt = null
            }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            
            if (level == -1 || scale <= 0) return
            
            val batteryPct = level * 100 / scale.toFloat()
            
            binding.tvBatteryPercentage.text = "${batteryPct.toInt()}%"
            binding.batteryProgressCircular.progress = batteryPct.toInt()

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            binding.tvChargingStatus.text = if (isCharging) "Charging" else "Discharging"
            
            val color = when {
                batteryPct <= 20 -> android.graphics.Color.RED
                batteryPct >= 90 -> android.graphics.Color.GREEN
                else -> ContextCompat.getColor(context, R.color.oni_accent)
            }
            binding.batteryProgressCircular.setIndicatorColor(color)

            refreshDetailedStats()
        }
    }

    // Reads a fresh snapshot and populates the detailed stats card (power, current,
    // voltage, energy, temperature, charging since, time to full). Ported/adapted from
    // Wattz's MainActivity, but computed locally here rather than over IPC since this
    // app runs the battery service in-process and it's optional.
    private fun refreshDetailedStats() {
        val snapshot = battery.snapshot()
        val indeterminate = getString(R.string.indeterminate)

        // Removed absolute values for Power and Current
        binding.tvVoltageValue.text = fmt(snapshot.volts) + "V"
        binding.tvEnergyValue.text = "${fmt(snapshot.energyWattHours)}Wh (${fmt(snapshot.energyAmpHours)}Ah)"
        binding.tvTemperatureValue.text = fmt(snapshot.celsius) + "\u00B0C"
        binding.tvChargingSinceValue.text = when (val at = pluggedInAt) {
            null -> indeterminate
            else -> LocalDateTime.ofInstant(at.toInstant(), at.zone).format(dateFmt)
        }
        binding.tvTimeToFullValue.text = when (val seconds = snapshot.secondsUntilCharged) {
            null -> indeterminate
            0.0 -> getString(R.string.fully_charged)
            else -> fmtSeconds(seconds)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = Prefs(this)
        battery = Battery(this)
        loadMetricsPrefs()

        checkPermissions()
        checkBatteryOptimization()
        updateServiceButton()

        binding.btnToggleService.setOnClickListener {
            val isRunning = prefs.isServiceEnabled()
            if (isRunning) {
                stopBatteryService()
            } else {
                if (checkOverlayPermission()) {
                    startBatteryService()
                }
            }
            updateServiceButton()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.tvOptimizationWarning.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun loadMetricsPrefs() {
        battery.currentScalar = prefs.getCurrentScalar().toDouble()
        battery.invertCurrent = prefs.isInvertCurrent()
    }

    private fun checkOverlayPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"))
                startActivity(intent)
                android.widget.Toast.makeText(this, "Please enable 'Display over other apps' to allow alerts anywhere.", android.widget.Toast.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            binding.tvOptimizationWarning.visibility = if (isIgnoring) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
            }
        }
    }

    private fun startBatteryService() {
        val intent = Intent(this, BatteryService::class.java)
        ContextCompat.startForegroundService(this, intent)
        prefs.setServiceEnabled(true)
    }

    private fun stopBatteryService() {
        val intent = Intent(this, BatteryService::class.java)
        stopService(intent)
        prefs.setServiceEnabled(false)
    }

    private fun updateServiceButton() {
        val isRunning = prefs.isServiceEnabled()
        binding.btnToggleService.text = if (isRunning) "Stop Monitor" else "Enable Monitor"
        binding.btnToggleService.setBackgroundColor(
            if (isRunning) ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else ContextCompat.getColor(this, R.color.oni_purple)
        )
    }

    override fun onResume() {
        super.onResume()
        loadMetricsPrefs()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        checkBatteryOptimization()
        refreshDetailedStats()
        metricsTask.start()
    }

    override fun onPause() {
        super.onPause()
        metricsTask.stop()
        unregisterReceiver(batteryReceiver)
    }
}
