package com.tg.batterychan.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.tg.batterychan.R
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.ui.SettingsActivity
import com.tg.batterychan.util.ImageLoader
import com.tg.batterychan.util.Launchers
import com.tg.batterychan.util.Media
import com.tg.batterychan.util.MediaKind
import com.tg.batterychan.util.NotificationHelper
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating "mini player": a muted, endlessly looping video / GIF / image drawn over the
 * home screen.
 *
 *  - Unlocked: drag to move, drag the corner dot to resize width and height freely,
 *    pinch to scale. Position and size are saved and restored (also after a reboot).
 *  - Locked: it cannot be moved or resized, and touches pass through to the launcher.
 *  - "Home screen only": it hides while another app is in front (needs Usage access).
 *  - It pauses while the screen is off.
 */
class MiniPlayerService : Service() {

    companion object {
        const val ACTION_SYNC = "com.tg.batterychan.MINI_SYNC"
        const val ACTION_TOGGLE_LOCK = "com.tg.batterychan.MINI_TOGGLE_LOCK"
        const val ACTION_STOP = "com.tg.batterychan.MINI_STOP"

        private const val NOTIFICATION_ID = 3 // BatteryService uses 1 and 2
        private const val SPEED_NOTIFICATION_ID = 4
        private const val TICK_MS = 800L
        private const val MIN_SIZE_DP = 40
        private const val DEFAULT_WIDTH_DP = 160
        private const val HANDLE_DP = 26
        private const val SPEED_PAD_H_DP = 6
        private const val SPEED_PAD_V_DP = 2
        private const val MIN_SPEED_SP = 6f
        private const val SNAP_DP = 20
        private const val HANDLE_MARGIN_DP = 8
        private const val HANDLE_TOUCH_DP = 56
        private const val FIRST_QUERY_MS = 24L * 60 * 60 * 1000
    }

    private lateinit var prefs: Prefs
    private lateinit var ownWm: WindowManager
    private lateinit var wm: WindowManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private var viaAccessibility = false
    private var opacity = 100
    private var cornerPct = 18
    private var pauseInSaver = true
    private var showOnLock = false
    private var onLockSurface = false // using the lock screen's own position / size
    private lateinit var battery: BatteryManager
    private var snapEdges = false
    private var pauseBelow = 0
    private var switchSecs = 0
    private var shuffle = false
    private var lastSwitchAt = 0L
    private var speedBits = false

    // --- Network speed meter ---
    private var speedUnder = false
    private var speedNotif = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSampleAt = 0L
    private var downBps = 0.0
    private var upBps = 0.0
    private var lastNotifText = ""
    private var labelDragging = false
    private var labelPinching = false
    private var speedAuto = true
    private var autoColor = Color.WHITE
    private var lastAutoAt = 0L
    private lateinit var scaleDetector: ScaleGestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private var density = 1f

    // --- Overlay views ---
    private var root: FrameLayout? = null
    private var speedLabel: TextView? = null
    private var imageView: ImageView? = null
    private var textureView: TextureView? = null
    private var handleView: View? = null
    private var borderDrawable: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    // --- Settings snapshot ---
    private var locked = false
    private var homeOnly = true
    private var hideInDrawer = false
    private var scaleMode = Prefs.MINI_SCALE_CROP

    // --- Window geometry in px ---
    private var curX = 0
    private var curY = 0
    private var curW = 0
    private var curH = 0

    // --- State ---
    private var screenOn = true
    private var shown = true
    private var mediaPath: String? = null
    private var mediaStamp = 0L

    // --- Video ---
    private var videoPath: String? = null
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var videoSurface: Surface? = null

    // --- "Is the home screen in front?" ---
    private var foregroundPkg: String? = null
    private var lastPollAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (root != null && wantAccessibilityWindow() != viaAccessibility) {
                applyAll() // accessibility service was turned on or off
                return
            }
            evaluate(poll = true)
            if (root != null && shown && switchSecs > 0 &&
                SystemClock.elapsedRealtime() - lastSwitchAt >= switchSecs * 1000L
            ) {
                advanceMedia()
            }
            sampleSpeed()
            sampleAutoColor()
            updateSpeedUi()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(tick)
                    applyPlayState()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    lastSampleAt = 0L // do not average the speed over the time the screen was off
                    if (root != null && switchSecs < 0) advanceMedia()
                    if (root != null) evaluate(poll = true)
                    handler.removeCallbacks(tick)
                    handler.postDelayed(tick, TICK_MS)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        density = resources.displayMetrics.density
        ownWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = ownWm
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        battery = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        NotificationHelper.ensureChannels(this)
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = power.isInteractive

        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (labelDragging || labelPinching) return false // two fingers that started on the speed meter resize the meter, not the player
                    pinchStartSpan = detector.currentSpan
                    pinchStartW = curW
                    pinchStartH = curH
                    pinchCenterX = curX + curW / 2f
                    pinchCenterY = curY + curH / 2f
                    return !locked
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (locked || pinchStartSpan <= 0f) return false
                    scaleTo(detector.currentSpan / pinchStartSpan)
                    return true
                }
            }
        )

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)

        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> prefs.setMiniLocked(!prefs.isMiniLocked(onLockSurface), onLockSurface)
            ACTION_STOP -> {
                prefs.setMiniEnabled(false)
                prefs.setSpeedInNotification(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!applyAll()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the screen size: keep the player fully on screen.
        // The saved portrait position is kept: only the on-screen copy is adjusted.
        root?.post {
            mediaPath?.let { loadGeometry(it, persist = false) }
            pushLayout()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        removeOverlay()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(SPEED_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Apply settings

    /** Reads every setting again and makes the overlay match. Returns false if it should not run. */
    private fun applyAll(): Boolean {
        speedNotif = prefs.isSpeedInNotification()
        speedUnder = prefs.isSpeedUnderPlayer()
        speedBits = prefs.isSpeedBits()
        speedAuto = prefs.isSpeedColorAuto()
        lastAutoAt = 0L

        val playlist = prefs.getMiniPlaylist()
        if (!MiniPlayerController.overlayWanted(this) || playlist.isEmpty()) {
            removeOverlay()
            if (!speedNotif) return false
            // Speed meter only: no mini player, just the notification.
            lastNotifText = ""
            updateNotification()
            updateSpeedIndicator(force = true)
            handler.removeCallbacks(tick)
            if (screenOn) handler.postDelayed(tick, TICK_MS)
            return true
        }
        shuffle = prefs.isMiniShuffle()
        switchSecs = prefs.getMiniSwitchSecs()
        // Keep showing the current item if it is still in the playlist.
        val path = mediaPath?.takeIf { it in playlist } ?: if (shuffle) playlist.random() else playlist.first()

        locked = prefs.isMiniLocked(onLockSurface)
        snapEdges = prefs.isMiniSnap()
        pauseBelow = prefs.getMiniPauseBelow()
        speedBits = prefs.isSpeedBits()
        homeOnly = prefs.isMiniHomeOnly()
        hideInDrawer = prefs.isMiniHideDrawer()
        LauncherState.watching = hideInDrawer
        scaleMode = prefs.getMiniScaleMode()
        showOnLock = prefs.isMiniShowOnLock()
        opacity = prefs.getMiniOpacity()
        cornerPct = prefs.getMiniCorner()
        pauseInSaver = prefs.isMiniPauseInSaver()

        // On the lock screen only an accessibility overlay can be seen. If that choice changed,
        // the window has to be created again with the other owner.
        val wantA11y = wantAccessibilityWindow()
        if (root != null && wantA11y != viaAccessibility) removeOverlay()
        viaAccessibility = wantA11y
        LauncherState.watching = hideInDrawer // removeOverlay() cleared it
        wm = if (wantA11y) {
            (LauncherState.service?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: ownWm
        } else {
            ownWm
        }

        loadGeometry(path)
        ensureOverlay()
        val stamp = File(path).lastModified()
        if (path != mediaPath || stamp != mediaStamp) {
            loadMedia(path, stamp)
            lastSwitchAt = SystemClock.elapsedRealtime()
        }
        applyScaleMode()
        applyChrome()
        if (speedUnder) fitSpeedText()

        evaluate(poll = true)
        pushLayout()
        updateNotification()
        updateSpeedIndicator(force = true)

        handler.removeCallbacks(tick)
        if (screenOn) handler.postDelayed(tick, TICK_MS)
        return true
    }

    private fun wantAccessibilityWindow(): Boolean = showOnLock && LauncherState.service != null

    private fun applyChrome() {
        handleView?.visibility = if (locked) View.GONE else View.VISIBLE
        root?.foreground = if (locked) null else borderDrawable
        styleSpeedLabel()
    }

    private fun applyScaleMode() {
        imageView?.scaleType = when (scaleMode) {
            Prefs.MINI_SCALE_STRETCH -> ImageView.ScaleType.FIT_XY
            Prefs.MINI_SCALE_FIT -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
        applyVideoFit()
    }

    // ------------------------------------------------------------------ Overlay window

    private fun ensureOverlay() {
        if (root != null) return

        val frame = FrameLayout(this)
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx(view.width, view.height))
            }
        }
        frame.clipToOutline = true

        val image = ImageView(this).apply { visibility = View.GONE }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        val texture = TextureView(this).apply {
            isOpaque = false
            visibility = View.GONE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    startVideo()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    applyVideoFit()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releasePlayer()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        frame.addView(texture, FrameLayout.LayoutParams(-1, -1))

        // Corner dot: drag it to resize width and height independently.
        val handleSize = (HANDLE_DP * density).roundToInt()
        val handleMargin = (HANDLE_MARGIN_DP * density).roundToInt()
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFFFFFF.toInt())
                setStroke((2 * density).roundToInt(), 0x99000000.toInt())
            }
        }
        frame.addView(
            handle,
            FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, handleMargin, handleMargin)
            }
        )

        // White outline shown while unlocked, so it is obvious the player can be edited.
        borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).roundToInt(), 0xCCFFFFFF.toInt())
        }

        // Download / upload speed, bold, inside the player. Drag it (while unlocked) to move it.
        val label = TextView(this).apply {
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(
                (SPEED_PAD_H_DP * density).roundToInt(), (SPEED_PAD_V_DP * density).roundToInt(),
                (SPEED_PAD_H_DP * density).roundToInt(), (SPEED_PAD_V_DP * density).roundToInt()
            )
        }
        frame.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        handle.bringToFront() // the resize dot stays above the speed meter

        frame.setOnTouchListener(touchListener)

        val lp = WindowManager.LayoutParams(
            curW,
            curH,
            if (viaAccessibility) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
            windowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = curX
            y = curY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root = frame
        speedLabel = label
        imageView = image
        textureView = texture
        handleView = handle
        params = lp
        attached = false
    }

    /** Roundness slider: 100% is half of the shorter side, so a square player becomes a circle. */
    private fun cornerRadiusPx(w: Int, h: Int): Float = cornerPct / 100f * min(w, h) / 2f

    /**
     * Android 12+ blocks taps that pass through an overlay more than 80% opaque, so while the
     * player is click-through (locked or hidden) it is capped at 80%. An accessibility overlay is
     * trusted and is not capped.
     */
    private fun effectiveAlpha(): Float {
        var a = opacity / 100f
        val clickThrough = (locked || !shown) && !viaAccessibility
        if (clickThrough && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) a = min(a, 0.8f)
        return a
    }

    private fun windowFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Without this a service-created window is not hardware accelerated and the
            // TextureView that shows the video stays black.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // Locked or hidden: let every touch fall through to the launcher / app underneath.
        if (locked || !shown) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /** Sends the current position, size and flags to the window (adds it the first time). */
    private fun pushLayout() {
        val frame = root ?: return
        val lp = params ?: return
        lp.x = curX
        lp.y = curY
        lp.width = curW
        lp.height = curH
        lp.flags = windowFlags()
        lp.alpha = effectiveAlpha()
        borderDrawable?.cornerRadius = cornerRadiusPx(curW, curH)
        frame.invalidateOutline()
        speedLabel?.visibility = if (speedUnder) View.VISIBLE else View.GONE
        placeSpeedLabel()
        try {
            if (attached) {
                wm.updateViewLayout(frame, lp)
            } else {
                wm.addView(frame, lp)
                attached = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(tick)
        LauncherState.watching = false
        releasePlayer()
        imageView?.let { ImageLoader.clear(it) }
        val frame = root
        if (frame != null && attached) {
            try {
                wm.removeView(frame)
            } catch (_: Exception) {
            }
        }
        attached = false
        root = null
        speedLabel = null
        imageView = null
        textureView = null
        handleView = null
        params = null
        mediaPath = null
        mediaStamp = 0L
        videoPath = null
    }

    // ------------------------------------------------------------------ Geometry

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun minSizePx(): Int = (MIN_SIZE_DP * density).roundToInt()

    /**
     * Loads the saved position / size of the surface in use (home screen or lock screen), or
     * picks a first-time default from the media's shape. The lock screen follows the home
     * screen until it is moved or resized there.
     */
    private fun loadGeometry(path: String, persist: Boolean = true) {
        val (sw, sh) = screenSize()
        val minPx = minSizePx()
        var w = prefs.getMiniWidth(onLockSurface)
        var h = prefs.getMiniHeight(onLockSurface)
        var x = prefs.getMiniX(onLockSurface)
        var y = prefs.getMiniY(onLockSurface)

        var followsHome = false
        if (onLockSurface && w <= 0 && x < 0) {
            w = prefs.getMiniWidth(false)
            h = prefs.getMiniHeight(false)
            x = prefs.getMiniX(false)
            y = prefs.getMiniY(false)
            followsHome = true
        }

        if (w <= 0 || h <= 0) {
            val ratio = Media.aspectRatio(path) ?: (16f / 9f)
            w = (DEFAULT_WIDTH_DP * density).roundToInt()
            h = (w / ratio).roundToInt()
            val maxH = (sh * 0.5f).roundToInt()
            if (h > maxH) {
                h = maxH
                w = (h * ratio).roundToInt()
            }
        }
        w = w.coerceIn(minPx, max(minPx, sw))
        h = h.coerceIn(minPx, max(minPx, sh))

        if (x < 0 || y < 0) {
            x = sw - w - (16 * density).roundToInt()
            y = (sh * 0.25f).roundToInt()
        }
        curW = w
        curH = h
        curX = x.coerceIn(0, max(0, sw - w))
        curY = y.coerceIn(0, max(0, sh - h))
        if (persist && !followsHome) persistGeometry()
    }

    private fun persistGeometry() {
        prefs.setMiniGeometry(curX, curY, curW, curH, onLockSurface)
    }

    private fun moveTo(x: Int, y: Int) {
        val (sw, sh) = screenSize()
        var nx = x.coerceIn(0, max(0, sw - curW))
        var ny = y.coerceIn(0, max(0, sh - curH))
        if (snapEdges) {
            // Magnet: close to an edge, the player sticks to it.
            val reach = SNAP_DP * density
            if (nx < reach) nx = 0 else if (sw - (nx + curW) < reach) nx = max(0, sw - curW)
            if (ny < reach) ny = 0 else if (sh - (ny + curH) < reach) ny = max(0, sh - curH)
        }
        curX = nx
        curY = ny
        pushLayout()
    }

    /** Resizes with the top-left corner fixed (what the corner dot does). */
    private fun resizeTo(w: Int, h: Int) {
        val (sw, sh) = screenSize()
        val minPx = minSizePx()
        curW = w.coerceIn(minPx, max(minPx, sw - curX))
        curH = h.coerceIn(minPx, max(minPx, sh - curY))
        pushLayout()
    }

    // Pinch state
    private var pinchStartSpan = 0f
    private var pinchStartW = 0
    private var pinchStartH = 0
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f

    /** Pinch: scales around the pinch start's centre, keeping the shape. */
    private fun scaleTo(factor: Float) {
        val (sw, sh) = screenSize()
        val minPx = minSizePx()
        val minF = max(minPx / pinchStartW.toFloat(), minPx / pinchStartH.toFloat())
        val maxF = max(minF, min(sw / pinchStartW.toFloat(), sh / pinchStartH.toFloat()))
        val f = factor.coerceIn(minF, maxF)
        val nw = (pinchStartW * f).roundToInt()
        val nh = (pinchStartH * f).roundToInt()
        curW = nw
        curH = nh
        curX = (pinchCenterX - nw / 2f).roundToInt().coerceIn(0, max(0, sw - nw))
        curY = (pinchCenterY - nh / 2f).roundToInt().coerceIn(0, max(0, sh - nh))
        pushLayout()
    }

    // ------------------------------------------------------------------ Touch

    private val touchListener = object : View.OnTouchListener {
        private val NONE = 0
        private val DRAG = 1
        private val RESIZE = 2
        private val PINCH = 3
        private val LABEL = 4
        private val LABEL_PINCH = 5
        private var labelPinchStartDist = 0f
        private var labelPinchStartSize = 12f
        private var labelDownX = 0f
        private var labelDownY = 0f
        private var labelStartTx = 0f
        private var labelStartTy = 0f

        private var mode = NONE
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (locked) return false
            scaleDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = curX
                    startY = curY
                    startW = curW
                    startH = curH
                    val zone = min(HANDLE_TOUCH_DP * density, min(curW, curH) * 0.4f)
                    val dotZone = (HANDLE_DP + HANDLE_MARGIN_DP + 6) * density
                    val slop = 12 * density
                    val label = speedLabel
                    val inDot = e.x >= curW - dotZone && e.y >= curH - dotZone
                    val inZone = e.x >= curW - zone && e.y >= curH - zone
                    val inLabel = speedUnder && label != null && label.visibility == View.VISIBLE &&
                        e.x >= label.translationX - slop && e.x <= label.translationX + label.width + slop &&
                        e.y >= label.translationY - slop && e.y <= label.translationY + label.height + slop
                    // The resize dot itself first, then the speed meter, then the rest of the corner, otherwise move the player.
                    mode = if (inDot) {
                        RESIZE
                    } else if (inLabel && label != null) {
                        labelDownX = e.rawX
                        labelDownY = e.rawY
                        labelStartTx = label.translationX
                        labelStartTy = label.translationY
                        labelDragging = true
                        LABEL
                    } else if (inZone) {
                        RESIZE
                    } else {
                        DRAG
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (mode == LABEL && e.pointerCount == 2) {
                        // Two fingers that started on the speed meter: pinch to change its size.
                        labelPinching = true
                        labelPinchStartDist = pointerDist(e)
                        labelPinchStartSize = prefs.getSpeedSize().toFloat()
                        mode = LABEL_PINCH
                    } else {
                        labelDragging = false
                        mode = PINCH
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleDetector.isInProgress) return true
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    when (mode) {
                        DRAG -> moveTo(startX + dx, startY + dy)
                        RESIZE -> resizeTo(startW + dx, startH + dy)
                        LABEL -> moveSpeedLabel(
                            labelStartTx + (e.rawX - labelDownX),
                            labelStartTy + (e.rawY - labelDownY)
                        )
                        LABEL_PINCH -> if (e.pointerCount >= 2 && labelPinchStartDist > 0f) {
                            val f = pointerDist(e) / labelPinchStartDist
                            prefs.setSpeedSize((labelPinchStartSize * f).roundToInt())
                            fitSpeedText()
                            speedLabel?.requestLayout()
                        }
                    }
                }
                // After a pinch, wait for a fresh touch so the remaining finger does not make it jump.
                MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == LABEL_PINCH) {
                        labelPinching = false
                        labelDragging = false
                        placeSpeedLabel()
                    }
                    mode = NONE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == LABEL) saveSpeedPos() else if (mode != LABEL_PINCH) persistGeometry()
                    labelDragging = false
                    labelPinching = false
                    mode = NONE
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ Media

    /** Playlist: moves on to the next picture / video (in order, or at random). */
    private fun advanceMedia() {
        val list = prefs.getMiniPlaylist()
        if (list.size < 2) return
        val current = mediaPath
        val next = if (shuffle) {
            list.filter { it != current }.random()
        } else {
            list[(list.indexOf(current) + 1) % list.size]
        }
        lastSwitchAt = SystemClock.elapsedRealtime()
        loadMedia(next, File(next).lastModified())
        applyScaleMode()
    }

    private fun loadMedia(path: String, stamp: Long) {
        val image = imageView ?: return
        val texture = textureView ?: return
        releasePlayer()
        ImageLoader.clear(image)
        mediaPath = path
        mediaStamp = stamp

        if (Media.kindOf(path) == MediaKind.VIDEO) {
            videoPath = path
            image.visibility = View.GONE
            texture.visibility = View.VISIBLE
            if (texture.isAvailable) startVideo()
        } else {
            // Still image, GIF or animated WebP. ImageLoader loops animations forever.
            videoPath = null
            texture.visibility = View.GONE
            image.visibility = View.VISIBLE
            val (sw, sh) = screenSize()
            ImageLoader.load(image, path, min(max(sw, sh), 1600))
        }
    }

    private fun startVideo() {
        val path = videoPath ?: return
        val texture = textureView ?: return
        val st = texture.surfaceTexture ?: return
        if (!texture.isAvailable) return

        releasePlayer()
        try {
            val surface = Surface(st)
            videoSurface = surface
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(path)
            mp.setSurface(surface)
            mp.isLooping = true
            // Always silent: this plays all day on the home screen.
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, _, _ -> applyVideoFit() }
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                p.setVolume(0f, 0f)
                playerReady = true
                applyVideoFit()
                applyPlayState()
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) releasePlayer()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
        }
    }

    /**
     * A TextureView stretches the video to its bounds, which is the "Stretch" option. For
     * "Fill" (crop) and "Fit" the picture is scaled back to its real aspect ratio.
     */
    private fun applyVideoFit() {
        val mp = player ?: return
        val view = textureView ?: return
        if (!playerReady) return
        try {
            val vw = mp.videoWidth
            val vh = mp.videoHeight
            val w = view.width
            val h = view.height
            if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
            val matrix = Matrix()
            if (scaleMode != Prefs.MINI_SCALE_STRETCH) {
                val sx = w.toFloat() / vw
                val sy = h.toFloat() / vh
                val scale = if (scaleMode == Prefs.MINI_SCALE_FIT) min(sx, sy) else max(sx, sy)
                matrix.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f)
            }
            view.setTransform(matrix)
        } catch (_: Exception) {
        }
    }

    private fun releasePlayer() {
        playerReady = false
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        videoSurface?.release()
        videoSurface = null
    }

    /** Plays only while the mini player is visible and the screen is on (saves battery). */
    /** True when "pause below N%" is on, the battery is under N% and the phone is not charging. */
    private fun batteryTooLow(): Boolean {
        if (pauseBelow <= 0 || battery.isCharging) return false
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1 until pauseBelow
    }

    private fun applyPlayState() {
        val saverOn = pauseInSaver && power.isPowerSaveMode
        val play = shown && screenOn && !saverOn && !batteryTooLow()

        val p = player
        if (p != null && playerReady) {
            try {
                if (play && !p.isPlaying) {
                    p.start()
                } else if (!play && p.isPlaying) {
                    p.pause()
                }
            } catch (_: Exception) {
            }
        }

        val d = imageView?.drawable as? AnimatedImageDrawable
        if (d != null) {
            if (play && !d.isRunning) {
                d.start()
            } else if (!play && d.isRunning) {
                d.stop()
            }
        }
    }

    // ------------------------------------------------------------------ Home screen detection

    private fun evaluate(poll: Boolean) {
        val frame = root ?: return

        // The lock screen has its own position and size.
        val keyguardLocked = keyguard.isKeyguardLocked
        val lockSurface = keyguardLocked && showOnLock && viaAccessibility
        if (lockSurface != onLockSurface) {
            onLockSurface = lockSurface
            locked = prefs.isMiniLocked(onLockSurface)
            applyChrome()
            mediaPath?.let { loadGeometry(it, persist = false) }
            pushLayout()
            updateNotification()
        }

        val usage = MiniPlayerController.hasUsageAccess(this)
        val needDetection = homeOnly && usage
        if (poll && usage && (homeOnly || hideInDrawer)) pollForeground()

        // Without Usage access we cannot tell, so the player simply stays visible.
        val onHome = !needDetection || isHomeInFront()

        // The app drawer lives inside the launcher, so only the accessibility service can see it.
        val launcherInFront = foregroundPkg?.let { it == packageName || it in Launchers.packages(this) } ?: true
        val inDrawer = hideInDrawer && LauncherState.connected &&
            LauncherState.drawerOpen && launcherInFront

        // Lock screen: shown only if the user asked for it (and the accessibility overlay exists).
        val newShown = if (keyguardLocked) {
            showOnLock && viaAccessibility
        } else {
            onHome && !inDrawer
        }
        frame.visibility = if (newShown) View.VISIBLE else View.GONE
        if (newShown != shown) {
            shown = newShown
            pushLayout() // updates the touchable flag
        }
        applyPlayState()
    }

    private fun pollForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val begin = if (lastPollAt == 0L) now - FIRST_QUERY_MS else lastPollAt - 2_000L
        try {
            val events = usm.queryEvents(begin, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundPkg = e.packageName
            }
            lastPollAt = now
        } catch (_: Exception) {
        }
    }

    /** Home = a launcher is in front. Our own app counts too, so settings changes are visible. */
    private fun isHomeInFront(): Boolean {
        val pkg = foregroundPkg ?: return true
        return pkg == packageName || pkg in launcherPackages()
    }

    private fun launcherPackages(): Set<String> = Launchers.packages(this)

    // ------------------------------------------------------------------ Network speed

    private fun sampleSpeed() {
        if (!speedUnder && !speedNotif) {
            lastSampleAt = 0L
            return
        }
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) { // not supported on this device
            downBps = 0.0
            upBps = 0.0
            return
        }
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastSampleAt
        if (lastSampleAt != 0L && dt in 200..5000) {
            downBps = max(0L, rx - lastRx) * 1000.0 / dt
            upBps = max(0L, tx - lastTx) * 1000.0 / dt
        } else {
            downBps = 0.0
            upBps = 0.0
        }
        lastRx = rx
        lastTx = tx
        lastSampleAt = now
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        if (speedBits) {
            val bits = bytesPerSecond * 8
            return when {
                bits >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bits / 1_000_000)
                bits >= 1_000 -> String.format(Locale.US, "%.0f Kbps", bits / 1_000)
                else -> String.format(Locale.US, "%.0f bps", bits)
            }
        }
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
        }
    }

    private fun pointerDist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
    )

    /**
     * Auto colour: looks at the picture / video frame right behind the speed meter and picks a
     * tint of that same colour that is light on dark footage and dark on bright footage.
     */
    private fun sampleAutoColor() {
        if (!speedUnder || !speedAuto || root == null || !shown || !screenOn) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoAt < 1500L) return
        lastAutoAt = now
        val w = curW
        val h = curH
        if (w <= 0 || h <= 0) return
        val bw = 64
        val bh = max(1, (bw * h / w.toFloat()).roundToInt())
        try {
            val texture = textureView
            val image = imageView
            val bmp: Bitmap? = if (videoPath != null && texture != null && texture.isAvailable) {
                texture.getBitmap(bw, bh)
            } else if (image != null && image.visibility == View.VISIBLE) {
                val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.scale(bw / w.toFloat(), bh / h.toFloat())
                image.draw(c)
                b
            } else {
                null
            }
            if (bmp == null) return
            // Theme colour: the most prominent *colourful* hue of the whole frame (e.g. Miku's teal
            // hair). Grey / white / black pixels are ignored so a white logo behind her does not win.
            val bins = 24
            val weight = DoubleArray(bins)
            val sumH = DoubleArray(bins)
            val sumS = DoubleArray(bins)
            val hsvPx = FloatArray(3)
            var lumSum = 0.0
            var n = 0
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    val p = bmp.getPixel(x, y)
                    if (Color.alpha(p) < 16) continue
                    Color.colorToHSV(p, hsvPx)
                    lumSum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
                    n++
                    if (hsvPx[1] < 0.35f || hsvPx[2] < 0.4f) continue
                    val bin = ((hsvPx[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                    // Bright, vivid colours (hair, glow) count far more than big dark areas
                    // (a dark blue logo), so the meter picks the colour that stands out.
                    val v2 = hsvPx[2] * hsvPx[2]
                    val w = (hsvPx[1] * v2 * v2).toDouble()
                    weight[bin] += w
                    sumH[bin] += hsvPx[0] * w
                    sumS[bin] += hsvPx[1] * w
                }
            }
            bmp.recycle()
            if (n == 0) return
            var best = -1
            for (i in 0 until bins) {
                // neighbours count too, so one hue split across two bins still wins
                val total = weight[i] + weight[(i + 1) % bins] + weight[(i + bins - 1) % bins]
                if (best < 0 || total > weight[best] + weight[(best + 1) % bins] + weight[(best + bins - 1) % bins]) best = i
            }
            val colourful = best >= 0 && weight[best] > n * 0.01
            val target: Int
            if (colourful) {
                val hue = (sumH[best] / weight[best]).toFloat()
                val sat = (sumS[best] / weight[best]).toFloat()
                // Vivid and bright so it reads like the theme colour on top of the video.
                target = Color.HSVToColor(floatArrayOf(hue, max(0.55f, min(sat, 0.9f)), 1f))
            } else {
                // Nothing colourful in the frame: fall back to a light / dark tone by brightness.
                target = if (lumSum / n > 0.5) 0xFF202020.toInt() else Color.WHITE
            }
            autoColor = blend(autoColor, target, 0.5f)
            styleSpeedLabel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Short form for the status bar icon: 16K, 4K, 1.2M ... */
    private fun compactSpeed(bytesPerSecond: Double): String {
        val v = if (speedBits) bytesPerSecond * 8 else bytesPerSecond
        val k = if (speedBits) 1000.0 else 1024.0
        return when {
            v >= k * k * 10 -> String.format(Locale.US, "%.0fM", v / (k * k))
            v >= k * k -> String.format(Locale.US, "%.1fM", v / (k * k))
            v >= k -> String.format(Locale.US, "%.0fK", v / k)
            else -> String.format(Locale.US, "%.0f", v)
        }
    }

    /** Status bar icon, same recipe as the power indicator: download on top, upload below. */
    private fun speedIcon(top: String, bottom: String): IconCompat {
        val w = (48f * density).toInt()
        val bitmap = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 25f * density
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        val widest = max(paint.measureText(top), paint.measureText(bottom))
        if (widest > w) paint.textSize = paint.textSize * w / widest
        canvas.drawText(top, w / 2f, w * 0.48f, paint)
        canvas.drawText(bottom, w / 2f, w * 0.98f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private var lastIconKey = ""

    /** The status bar speed icon: its own ongoing notification, just like the power indicator. */
    private fun updateSpeedIndicator(force: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!speedNotif) {
            if (lastIconKey.isNotEmpty()) nm.cancel(SPEED_NOTIFICATION_ID)
            lastIconKey = ""
            return
        }
        val top = compactSpeed(downBps)
        val bottom = compactSpeed(upBps)
        val key = "$top|$bottom"
        if (!force && key == lastIconKey) return
        lastIconKey = key
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            val n = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SPEED)
                .setSmallIcon(speedIcon(top, bottom))
                .setContentTitle(speedText())
                .setContentText("Network speed")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
            nm.notify(SPEED_NOTIFICATION_ID, n)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun speedText(): String = "\u2193 ${formatSpeed(downBps)}   \u2191 ${formatSpeed(upBps)}"

    /** Colour, size and (while unlocked) a faint box so it is obvious the meter can be dragged. */
    private fun styleSpeedLabel() {
        val label = speedLabel ?: return
        val color = if (speedAuto) autoColor else prefs.getSpeedColor()
        label.setTextColor(color)
        // Light text gets a dark shadow, dark text a light one, so it reads on any picture.
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        label.setShadowLayer(5f * density, 0f, 1f * density, if (luminance > 0.35) Color.BLACK else Color.WHITE)
        label.background = if (locked) {
            null
        } else {
            GradientDrawable().apply {
                cornerRadius = 6f * density
                setColor(0x33000000)
            }
        }
        fitSpeedText()
    }

    private fun speedTextTwoLines(): String = "\u2193 ${formatSpeed(downBps)}\n\u2191 ${formatSpeed(upBps)}"

    /**
     * Applies the chosen text size. If one line does not fit inside the player the text is split
     * into two lines (down above up); only then is it shrunk, so a bigger size really gets bigger.
     */
    private fun fitSpeedText() {
        val label = speedLabel ?: return
        val wanted = prefs.getSpeedSize().toFloat()
        val available = (curW - 2 * SPEED_PAD_H_DP * density).coerceAtLeast(1f)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, wanted)
        var text = speedText()
        var width = label.paint.measureText(text)
        if (width > available) {
            text = speedTextTwoLines()
            width = text.split('\n').maxOf { label.paint.measureText(it) }
        }
        label.text = text
        if (width > available) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(MIN_SPEED_SP, wanted * available / width))
        }
    }

    /** Puts the label at its saved spot (fractions of the free space inside the player). */
    private fun placeSpeedLabel() {
        val label = speedLabel ?: return
        if (!speedUnder || labelDragging) return
        fitSpeedText()
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        label.translationX = prefs.getSpeedPosX() * max(0, curW - label.measuredWidth)
        label.translationY = prefs.getSpeedPosY() * max(0, curH - label.measuredHeight)
    }

    private fun moveSpeedLabel(tx: Float, ty: Float) {
        val label = speedLabel ?: return
        label.translationX = tx.coerceIn(0f, max(0, curW - label.width).toFloat())
        label.translationY = ty.coerceIn(0f, max(0, curH - label.height).toFloat())
    }

    private fun saveSpeedPos() {
        val label = speedLabel ?: return
        val freeX = max(0, curW - label.width)
        val freeY = max(0, curH - label.height)
        prefs.setSpeedPos(
            if (freeX > 0) label.translationX / freeX else 0.5f,
            if (freeY > 0) label.translationY / freeY else 0.5f
        )
    }

    private fun updateSpeedUi() {
        if (!speedUnder && !speedNotif) return
        val text = speedText()
        if (speedUnder) placeSpeedLabel() // also refreshes the text
        if (speedNotif && text != lastNotifText) {
            lastNotifText = text
            updateNotification()
            updateSpeedIndicator()
        }
    }

    // ------------------------------------------------------------------ Notification

    private fun buildNotification(): Notification {
        val overlayOn = MiniPlayerController.overlayWanted(this)
        val speedOn = prefs.isSpeedInNotification()
        val isLocked = prefs.isMiniLocked(onLockSurface)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), flags)
        val lock = PendingIntent.getService(
            this, 1,
            Intent(this, MiniPlayerService::class.java).setAction(ACTION_TOGGLE_LOCK), flags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MiniPlayerService::class.java).setAction(ACTION_STOP), flags
        )
        val builder = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_MINI)
            .setSmallIcon(R.drawable.ic_play_arrow)
            .setContentTitle(
                when {
                    speedOn -> speedText()
                    else -> "Mini player is on"
                }
            )
            .setContentText(
                when {
                    !overlayOn -> "Network speed meter"
                    isLocked -> "Mini player: locked in place" + if (onLockSurface) " (lock screen)" else ""
                    else -> "Mini player: unlocked, drag to move, drag the corner dot to resize"
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (overlayOn) builder.addAction(0, if (isLocked) "Unlock" else "Lock", lock)
        builder.addAction(0, "Turn off", stop)
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
