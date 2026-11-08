package com.tg.batterychan

import android.app.Application
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.ui.ThemeUtils
import com.tg.batterychan.util.NotificationHelper

class BatteryChanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeUtils.apply(Prefs(this))
        NotificationHelper.ensureChannels(this)
    }
}
