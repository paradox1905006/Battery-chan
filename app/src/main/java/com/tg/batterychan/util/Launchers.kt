package com.tg.batterychan.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/** Which installed apps are home screens (launchers) and keyboards. Cached for a minute. */
object Launchers {
    private const val CACHE_MS = 60_000L

    private var launchers: Set<String> = emptySet()
    private var launchersAt = 0L
    private var keyboards: Set<String> = emptySet()
    private var keyboardsAt = 0L

    @Suppress("DEPRECATION")
    fun packages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (launchers.isEmpty() || now - launchersAt > CACHE_MS) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            launchers = context.packageManager
                .queryIntentActivities(home, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .toSet()
            launchersAt = now
        }
        return launchers
    }

    fun keyboardPackages(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (keyboards.isEmpty() || now - keyboardsAt > CACHE_MS) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboards = imm.enabledInputMethodList.map { it.packageName }.toSet()
            keyboardsAt = now
        }
        return keyboards
    }
}
