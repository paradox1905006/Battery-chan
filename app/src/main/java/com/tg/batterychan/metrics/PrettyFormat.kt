package com.tg.batterychan.metrics

import kotlin.math.absoluteValue

// Ported from Wattz (dubrowgn.wattz.PrettyFormat).

fun fmt(v: Double?): String {
    if (v == null)
        return "- "

    val abs = v.absoluteValue
    return when {
        abs >= 100.0 -> "%.0f".format(v)
        abs >= 10.0 -> "%.1f".format(v)
        abs >= 1.0 -> "%.2f".format(v)
        abs > 0.0 -> "%.3f".format(v)
        else -> "0.0"
    }
}

fun fmtSeconds(seconds: Double?): String {
    if (seconds == null)
        return ""

    var secs = seconds.toInt()
    if (secs < 60)
        return "${secs}s"

    var mins = secs / 60
    secs %= 60
    if (mins < 60)
        return "${mins}m ${secs}s"

    val hrs = mins / 60
    mins %= 60
    return "${hrs}h ${mins}m ${secs}s"
}
