package com.tg.batterychan.service

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.tg.batterychan.data.Prefs
import java.io.File

/** Starts / stops / refreshes [MiniPlayerService] from settings, boot and anywhere else. */
object MiniPlayerController {

    /** True when the mini player is switched on, has media, and may draw over other apps. */
    fun overlayWanted(context: Context): Boolean {
        val prefs = Prefs(context)
        val path = prefs.getMiniMediaPath()
        return prefs.isMiniEnabled() &&
            Settings.canDrawOverlays(context) &&
            path != null && File(path).exists()
    }

    /** The service also runs for the speed meter alone (notification only, no mini player). */
    fun shouldRun(context: Context): Boolean =
        overlayWanted(context) || Prefs(context).isSpeedInNotification()

    /**
     * Makes the service match the saved settings: starts it (or tells the running one to
     * re-read them), or stops it when it should not run.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, MiniPlayerService::class.java)
        if (shouldRun(app)) {
            try {
                ContextCompat.startForegroundService(app, intent.setAction(MiniPlayerService.ACTION_SYNC))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            app.stopService(intent)
        }
    }

    /** "Usage access" is how the app finds out which app is in front (home screen or not). */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** True when the user has switched on the "Battery Chan mini player" accessibility service. */
    fun hasAccessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val name = android.content.ComponentName(context, LauncherWatchService::class.java)
        return enabled.split(':').any {
            android.content.ComponentName.unflattenFromString(it) == name
        }
    }
}
