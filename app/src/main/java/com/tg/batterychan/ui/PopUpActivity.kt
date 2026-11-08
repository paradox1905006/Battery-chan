package com.tg.batterychan.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import com.tg.batterychan.data.Prefs
import com.tg.batterychan.databinding.ActivityPopUpBinding
import com.tg.batterychan.util.EventAudioPlayer
import com.tg.batterychan.util.ImageLoader
import com.tg.batterychan.util.Media
import com.tg.batterychan.util.MediaKind
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PopUpActivity : BaseActivity() {

    // How the picture / video fills the card. Chosen per event in Settings (Prefs.SCALE_*).
    private var scaleMode = Prefs.SCALE_FILL

    private lateinit var binding: ActivityPopUpBinding
    private lateinit var prefs: Prefs
    private lateinit var audioPlayer: EventAudioPlayer
    private val dismissRunnable = Runnable { if (!isFinishing) stopAndFinish() }

    // Video playback (only used when the event's media is a video).
    private var videoPath: String? = null
    private var videoMuted = false
    private var videoPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null

    // The pop-up manages its own full-screen window.
    override val useEdgeToEdge: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        audioPlayer = EventAudioPlayer(this)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            }

            // Show over lockscreen and turn screen on
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            binding = ActivityPopUpBinding.inflate(layoutInflater)
            setContentView(binding.root)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.root.post {
                    window.insetsController?.let {
                        it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        it.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }

            binding.tvEventVideo.isOpaque = false // poster frame sits underneath, same fit as the video
            binding.tvEventVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    startVideoIfReady()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    applyVideoFit()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releaseVideoPlayer()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }

            binding.btnDismiss.setOnClickListener { stopAndFinish() }
            showEvent(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }

    /**
     * The pop-up is singleTop, so a second event arriving while it is open lands here.
     * Show the newest event and replace (not overlap) the sound.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showEvent(intent)
    }

    private fun showEvent(intent: Intent) {
        val eventTitle = intent.getStringExtra("event_title") ?: "Battery Event"
        val imagePath = intent.getStringExtra("image_path")
        val audioPath = intent.getStringExtra("audio_path")
        val customText = intent.getStringExtra("custom_text")
        val duration = intent.getIntExtra("duration", 10)
        scaleMode = intent.getIntExtra("scale_mode", Prefs.SCALE_FILL)

        binding.tvEventTitle.text = eventTitle
        binding.tvCustomMessage.text = customText
        binding.tvCustomMessage.visibility =
            if (customText.isNullOrEmpty()) View.GONE else View.VISIBLE

        releaseVideoPlayer()
        videoPath = null
        binding.ivEventImage.visibility = View.VISIBLE
        // The poster frame / image uses the same fit as the video, so there is no jump.
        binding.ivEventImage.scaleType = when (scaleMode) {
            Prefs.SCALE_STRETCH -> ImageView.ScaleType.FIT_XY
            Prefs.SCALE_FIT -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
        binding.ivEventImage.setBackgroundColor(if (scaleMode == Prefs.SCALE_FIT) Color.BLACK else Color.TRANSPARENT)

        if (imagePath != null && File(imagePath).exists()) {
            // Image / GIF: ImageLoader animates GIFs. Video: ImageLoader shows the poster
            // frame while the video below starts up.
            ImageLoader.load(binding.ivEventImage, imagePath, 1600)

            if (Media.kindOf(imagePath) == MediaKind.VIDEO) {
                videoPath = imagePath
                // If the event has its own sound, keep the video silent so they don't clash.
                videoMuted = audioPath != null && File(audioPath).exists()
                binding.tvEventVideo.visibility = View.VISIBLE
                startVideoIfReady()
            } else {
                binding.tvEventVideo.visibility = View.GONE
            }
        } else {
            ImageLoader.clear(binding.ivEventImage)
            binding.tvEventVideo.visibility = View.GONE
        }

        // play() stops whatever was playing, so there is only ever one sound.
        if (audioPath != null && File(audioPath).exists()) {
            audioPlayer.play(audioPath, prefs.isBoostVolume()) {
                if (prefs.isAutoDismissOnAudio()) stopAndFinish()
            }
        } else {
            audioPlayer.stop()
        }

        binding.root.removeCallbacks(dismissRunnable)
        binding.root.postDelayed(dismissRunnable, duration * 1000L)
    }

    /** Starts the looping video once the TextureView has a surface (and a video is wanted). */
    private fun startVideoIfReady() {
        val path = videoPath ?: return
        val view = binding.tvEventVideo
        val texture = view.surfaceTexture ?: return
        if (!view.isAvailable) return

        releaseVideoPlayer()
        try {
            val surface = Surface(texture)
            videoSurface = surface
            val mp = MediaPlayer()
            videoPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(path)
            mp.setSurface(surface)
            mp.isLooping = true
            if (videoMuted) mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, _, _ -> applyVideoFit() }
            mp.setOnPreparedListener {
                applyVideoFit()
                it.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                releaseVideoPlayer() // poster frame stays visible
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releaseVideoPlayer()
        }
    }

    /**
     * TextureView stretches the video to its own bounds by default, which is exactly
     * [Prefs.SCALE_STRETCH]. For fill / fit the content is scaled back to the real aspect ratio.
     */
    private fun applyVideoFit() {
        val mp = videoPlayer ?: return
        val view = binding.tvEventVideo
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        val w = view.width
        val h = view.height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return

        val matrix = Matrix()
        if (scaleMode != Prefs.SCALE_STRETCH) {
            val sx = w.toFloat() / vw
            val sy = h.toFloat() / vh
            val scale = if (scaleMode == Prefs.SCALE_FIT) min(sx, sy) else max(sx, sy)
            matrix.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f)
        }
        view.setTransform(matrix)
    }

    private fun releaseVideoPlayer() {
        videoPlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        videoPlayer = null
        videoSurface?.release()
        videoSurface = null
    }

    private fun stopAndFinish() {
        audioPlayer.stop()
        releaseVideoPlayer()
        finish()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(dismissRunnable)
        if (::audioPlayer.isInitialized) audioPlayer.stop()
        releaseVideoPlayer()
        if (::binding.isInitialized) ImageLoader.clear(binding.ivEventImage)
        super.onDestroy()
    }
}
