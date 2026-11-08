package com.tg.batterychan.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

// Ported from Wattz (dubrowgn.wattz.Battery / PlugType).

enum class PlugType {
    Ac,
    Dock,
    Unknown,
    Usb,
    Wireless;

    companion object {
        fun fromRaw(plugType: Int?): PlugType? {
            return when (plugType) {
                null, 0 -> null
                BatteryManager.BATTERY_PLUGGED_AC -> Ac
                BatteryManager.BATTERY_PLUGGED_DOCK -> Dock
                BatteryManager.BATTERY_PLUGGED_USB -> Usb
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> Wireless
                else -> Unknown
            }
        }
    }
}

class Battery(private val ctx: Context) {
    private val mgr = ctx.applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    var currentScalar = 1.0
    var invertCurrent = false

    private fun prop(id: Int): Long? {
        val v = mgr.getLongProperty(id)
        if (v == Long.MIN_VALUE) return null
        return v
    }

    fun snapshot(): BatterySnapshot {
        val intent = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        // Try standard BatteryManager properties first
        var current = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == null || current == 0L) {
            val avg = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (avg != null && avg != 0L) current = avg
        }

        // If still 0 or null, check non-standard Intent extras used by many OEMs
        if (current == null || current == 0L) {
            val extras = listOf("current_now", "current_avg", "BatteryCurrent", "current_mA")
            for (extra in extras) {
                val value = intent?.getIntExtra(extra, Int.MIN_VALUE)
                if (value != null && value != Int.MIN_VALUE && value != 0) {
                    // Intent extras are often in mA, whereas Property is usually in uA.
                    // If it's a small value, it's likely mA, so we scale it to uA for the engine.
                    current = if (Math.abs(value) < 20000) value.toLong() * 1000 else value.toLong()
                    break
                }
            }
        }

        // Android says CURRENT_NOW is in microamps, but many phones actually report milliamps.
        // A real microamp reading while the phone is in use is tens of thousands or more
        // (50 mA = 50000 uA), while a milliamp reading never gets near 10000 (10 A), so a
        // small magnitude means the value is really mA. Skipped when the user has picked a
        // manual power-scalar workaround, so that setting always wins.
        val rawCurrent = current
        if (currentScalar == 1.0 && rawCurrent != null) {
            val magnitude = Math.abs(rawCurrent)
            if (magnitude in 1L..9999L) current = rawCurrent * 1000L
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it != -1 }?.toDouble()
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it != -1 }?.toDouble()
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it != -1 }?.toLong()
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it != -1 }?.toLong()
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it != -1 }

        return BatterySnapshot(
            chargeTimeRemainingRaw = mgr.computeChargeTimeRemaining(),
            currentRaw = current,
            currentScalar = currentScalar,
            energyRaw = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            level = if (level != null && scale != null) level / scale else null,
            invertCurrent = invertCurrent,
            isChargingRaw = mgr.isCharging,
            plugType = PlugType.fromRaw(plugged),
            tempRaw = temperature,
            voltsRaw = voltage,
        )
    }
}
