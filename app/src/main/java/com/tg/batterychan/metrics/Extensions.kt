package com.tg.batterychan.metrics

// Null-safe arithmetic helpers used throughout BatterySnapshot's derived properties.
// Ported from Wattz (dubrowgn.wattz.Extensions).

fun Double?.div(v: Double?): Double? {
    if (this == null || v == null || v == 0.0)
        return null

    return this / v
}

fun Double?.times(v: Double?): Double? {
    if (this == null || v == null)
        return null

    return this * v
}
