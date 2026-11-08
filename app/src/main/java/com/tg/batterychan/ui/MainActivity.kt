package com.tg.batterychan.ui

import android.content.*
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import com.google.android.material.color.MaterialColors
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

class MainActivity : BaseActivity() {

    private companion object {
        const val GREEN = 0xFF2E9E5B.toInt()
        const val ORANGE = 0xFFE8803A.toInt()
    }

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
                batteryPct <= 20 -> MaterialColors.getColor(
                    binding.root, androidx.appcompat.R.attr.colorError
                )
                batteryPct >= 90 -> MaterialColors.harmonizeWithPrimary(context, GREEN)
                else -> MaterialColors.getColor(
                    binding.root, androidx.appcompat.R.attr.colorPrimary
                )
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

        updateLivePower(snapshot)

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

    // Live watt meter. Power is shown as a positive number; the label says which way the
    // energy is flowing. Direction comes from the plug state (not the current's sign) so
    // it stays right on phones that report current backwards.
    private fun updateLivePower(snapshot: com.tg.batterychan.metrics.BatterySnapshot) {
        val watts = snapshot.watts
        if (watts == null) {
            binding.tvLivePowerValue.text = "-- W"
            binding.tvLivePowerLabel.text = "Not supported on this phone"
            binding.tvLivePowerDetail.text = ""
            return
        }

        val plugged = snapshot.isChargingRaw || snapshot.plugType != null
        val absWatts = kotlin.math.abs(watts)
        binding.tvLivePowerValue.text = "%.2f W".format(absWatts)
        binding.tvLivePowerLabel.text = if (plugged) "Charging" else "Draining"
        binding.tvLivePowerLabel.setTextColor(if (plugged) GREEN else ORANGE)

        val mA = snapshot.milliamps?.let { kotlin.math.abs(it) }
        binding.tvLivePowerDetail.text = "${fmt(mA)} mA \u00B7 ${fmt(snapshot.volts)} V"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        
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
        val button = binding.btnToggleService
        button.text = if (isRunning) "Stop Monitor" else "Enable Monitor"
        val container = if (isRunning) {
            com.google.android.material.R.attr.colorErrorContainer
        } else {
            androidx.appcompat.R.attr.colorPrimary
        }
        val content = if (isRunning) {
            com.google.android.material.R.attr.colorOnErrorContainer
        } else {
            com.google.android.material.R.attr.colorOnPrimary
        }
        button.backgroundTintList =
            android.content.res.ColorStateList.valueOf(MaterialColors.getColor(button, container))
        val onColor = MaterialColors.getColor(button, content)
        button.setTextColor(onColor)
        button.iconTint = android.content.res.ColorStateList.valueOf(onColor)
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
