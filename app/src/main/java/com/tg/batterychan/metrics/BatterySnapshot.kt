package com.tg.batterychan.metrics

import kotlin.math.abs

class BatterySnapshot(
    private val currentScalar: Double,
    private val invertCurrent: Boolean,
    val chargeTimeRemainingRaw: Long,
    val currentRaw: Long?,
    val energyRaw: Long?,
    val level: Double?,
    val isChargingRaw: Boolean,
    val plugType: PlugType?,
    val tempRaw: Long?,
    val voltsRaw: Long?,
) {
    val microamps: Double? get() {
        val raw = currentRaw?.toDouble() ?: return null
        // Default sign is 1.0 (Standard: Charging > 0, Discharging < 0)
        val sign = if (invertCurrent) -1.0 else 1.0
        return raw * currentScalar * sign
    }
    
    val milliamps: Double? get() = microamps?.let { it / 1000.0 }
    val amps: Double? get() = microamps?.let { it / 1_000_000.0 }

    val volts: Double? get() {
        val raw = voltsRaw?.toDouble() ?: return null
        return if (raw > 100) raw / 1000.0 else raw
    }

    val watts: Double? get() {
        val a = amps ?: return null
        val v = volts ?: return null
        return a * v
    }

    val energyAmpHours: Double? get() = energyRaw?.toDouble()?.let { it / 1_000_000.0 }
    
    val energyWattHours: Double? get() {
        val ah = energyAmpHours ?: return null
        val v = volts ?: return null
        return ah * v
    }

    val levelPercent: Double? get() = level?.let { it * 100.0 }
    val celsius: Double? get() = tempRaw?.toDouble()?.let { it / 10.0 }

    val charging: Boolean get() {
        if (isChargingRaw) return true
        if (plugType != null && plugType != PlugType.Unknown) return true
        
        val ma = milliamps ?: return false
        return abs(ma) > 10.0
    }

    val secondsUntilCharged: Double? get() {
        if (!charging || chargeTimeRemainingRaw <= 0) return null
        return chargeTimeRemainingRaw.toDouble() / 1000.0
    }
}
