package com.tg.batterychan.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.util.Launchers

/** Shared between [LauncherWatchService] and [MiniPlayerService]. */
object LauncherState {
    /** True while the accessibility service is switched on by the user. */
    @Volatile var connected = false

    /** The mini player sets this while it wants to know about the app drawer. */
    @Volatile var watching = false

    /** True while the launcher's app drawer is open. */
    @Volatile var drawerOpen = false

    /** The running accessibility service. Its windows can be drawn above the lock screen. */
    @Volatile var service: AccessibilityService? = null
}

/**
 * Used for two things: to tell "home screen" from "app drawer" inside the launcher, and as
 * the window owner for the mini player when it is shown on the lock screen (normal overlays
 * are hidden by the lock screen, accessibility overlays are not). It reads the
 * launcher's screen for the drawer, and does nothing for any other app. It never stores,
 * sends or logs what it sees.
 */
class LauncherWatchService : AccessibilityService() {

    private companion object {
        // View ids that launchers give their app drawer. Matched as a part of the id.
        val DRAWER_IDS = listOf("all_apps", "apps_list", "app_drawer", "apps_view", "apps_recycler", "drawer")

        // Ids that contain the words above but are buttons / hints on the home screen.
        val NOT_DRAWER = listOf("button", "handle", "hint", "icon", "fab", "toggle", "tab")

        const val MAX_NODES = 800
        const val MAX_DEPTH = 30
        const val MIN_GAP_MS = 250L
    }

    private var lastCheck = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LauncherState.connected = true
        LauncherState.service = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LauncherState.connected = false
        LauncherState.service = null
        LauncherState.drawerOpen = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !LauncherState.watching) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg !in Launchers.packages(this)) {
            // Another app (not the keyboard or the status bar) came forward: the drawer is gone.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != "com.android.systemui" &&
                pkg !in Launchers.keyboardPackages(this)
            ) {
                LauncherState.drawerOpen = false
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheck < MIN_GAP_MS) return
        lastCheck = now

        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            val marker = Prefs(this).getMiniDrawerMarker()
            LauncherState.drawerOpen = looksLikeDrawer(root, marker, LauncherState.drawerOpen)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun looksLikeDrawer(root: AccessibilityNodeInfo, marker: String, wasOpen: Boolean): Boolean {
        val budget = intArrayOf(MAX_NODES)
        return scan(root, 0, budget, marker.trim(), wasOpen)
    }

    private fun scan(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray,
        marker: String,
        wasOpen: Boolean
    ): Boolean {
        if (budget[0]-- <= 0 || depth > MAX_DEPTH) return false

        if (node.isVisibleToUser) {
            val id = node.viewIdResourceName?.lowercase()
            if (id != null && DRAWER_IDS.any { id.contains(it) } && NOT_DRAWER.none { id.contains(it) }) {
                return true
            }
            if (marker.isNotEmpty() &&
                (node.text?.contains(marker, ignoreCase = true) == true ||
                    node.hintText?.contains(marker, ignoreCase = true) == true ||
                    node.contentDescription?.contains(marker, ignoreCase = true) == true)
            ) {
                return true
            }
            // While typing in the drawer's search box the hint text is gone, so a focused
            // text field keeps an already-open drawer "open".
            if (wasOpen && node.isEditable && node.isFocused) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scan(child, depth + 1, budget, marker, wasOpen)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found) return true
        }
        return false
    }
}
