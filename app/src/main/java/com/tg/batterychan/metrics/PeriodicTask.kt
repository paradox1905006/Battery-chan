package com.tg.batterychan.metrics

import android.os.Handler
import android.os.Looper

// Ported from Wattz (dubrowgn.wattz.PeriodicTask). Drives the live power-indicator
// notification tick while the service is running and the screen is on.
class PeriodicTask(callback: () -> Unit, private val intervalMs: Long) {
    private val ticker = Handler(Looper.getMainLooper())
    private val callback = callback
    private val runnable = Runnable { start() }

    fun stop() {
        ticker.removeCallbacks(runnable)
    }

    private fun tick() {
        callback()
        ticker.postDelayed(runnable, intervalMs)
    }

    fun start() {
        stop()
        tick()
    }
}
