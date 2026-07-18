package com.tg.batterychan.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.tg.batterychan.databinding.ActivityPopUpBinding
import java.io.File

class PopUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPopUpBinding
    private lateinit var prefs: com.tg.batterychan.data.Prefs
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var originalVolume: Int = -1
    private lateinit var audioManager: android.media.AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = com.tg.batterychan.data.Prefs(this)
        audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        
        try {
            // Full screen setup
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
            @Suppress("DEPRECATION")
            setShowWhenLocked(true)
            @Suppress("DEPRECATION")
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            binding = ActivityPopUpBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Hide system bars after content is set
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.root.post {
                    window.insetsController?.let {
                        it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                        it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }

            val eventTitle = intent.getStringExtra("event_title") ?: "Battery Event"
            val imagePath = intent.getStringExtra("image_path")
            val audioPath = intent.getStringExtra("audio_path")
            val customText = intent.getStringExtra("custom_text")
            val duration = intent.getIntExtra("duration", 10)

            binding.tvEventTitle.text = eventTitle
            binding.tvCustomMessage.text = customText
            binding.tvCustomMessage.visibility = if (customText.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE

            if (imagePath != null && File(imagePath).exists()) {
                Glide.with(this)
                    .load(imagePath)
                    .centerCrop()
                    .into(binding.ivEventImage)
            }

            if (audioPath != null && File(audioPath).exists()) {
                // Save original volume and set to max only if we have audio to play
                originalVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, 0)

                playAudio(audioPath)
            }

            binding.btnDismiss.setOnClickListener {
                stopAndFinish()
            }

            binding.root.postDelayed({
                if (!isFinishing) stopAndFinish()
            }, duration * 1000L)
            
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }

    private fun playAudio(path: String) {
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(path)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setVolume(1.0f, 1.0f) // Maximize internal player volume
                prepare()
                start()
                setOnCompletionListener {
                    restoreVolume()
                    if (prefs.isAutoDismissOnAudio()) {
                        stopAndFinish()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreVolume() {
        if (originalVolume != -1) {
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
            originalVolume = -1
        }
    }

    private fun stopAndFinish() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            restoreVolume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        finish()
    }

    override fun onDestroy() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            restoreVolume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        super.onDestroy()
    }
}
