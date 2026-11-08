package com.tg.batterychan.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.tg.batterychan.R
import com.tg.batterychan.data.Prefs

/**
 * Applies the user's appearance choices (Material You dynamic colors, pure-black theme)
 * and recreates itself if they changed while another screen was in front.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedThemeSignature = ""

    protected open val useEdgeToEdge: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Prefs(this)
        appliedThemeSignature = prefs.themeSignature()

        if (prefs.isDynamicColor()) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        if (prefs.getThemeMode() == Prefs.THEME_BLACK) {
            theme.applyStyle(R.style.ThemeOverlay_BatteryChan_Black, true)
        }

        super.onCreate(savedInstanceState)
        if (useEdgeToEdge) enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).themeSignature() != appliedThemeSignature) recreate()
    }

    /** Pads [root] by the system bars / cutout / keyboard so content never sits under them. */
    protected fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }
}
