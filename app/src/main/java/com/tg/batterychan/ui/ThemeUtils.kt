package com.tg.batterychan.ui

import androidx.appcompat.app.AppCompatDelegate
import com.tg.batterychan.data.Prefs

object ThemeUtils {
    /** Black (AMOLED) is a dark theme with pure black surfaces, so it forces night mode. */
    fun nightModeFor(mode: Int): Int = when (mode) {
        Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Prefs.THEME_DARK, Prefs.THEME_BLACK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun apply(prefs: Prefs) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(prefs.getThemeMode()))
    }
}
