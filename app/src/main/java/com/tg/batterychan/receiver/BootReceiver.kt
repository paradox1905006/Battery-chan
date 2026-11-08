package com.tg.batterychan.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.service.BatteryService
import com.tg.batterychan.service.MiniPlayerController

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            val prefs = Prefs(context)
            if (prefs.isServiceEnabled()) {
                val serviceIntent = Intent(context, BatteryService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Bring the home screen mini player back after a reboot / app update.
            if (prefs.isMiniEnabled() || prefs.isSpeedInNotification()) MiniPlayerController.sync(context)
        }
    }
}
