package com.tg.batterychan.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Plays one event sound at a time. Starting a new sound always stops and releases the
 * previous one first, which is what prevents the overlapping "echo" playback.
 */
class EventAudioPlayer(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var originalVolume = -1
    private var focusRequest: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()

    fun play(path: String, boostVolume: Boolean, onComplete: () -> Unit) {
        stop() // never let two players overlap

        if (boostVolume) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
        }
        requestFocus()

        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attributes)
            mp.setDataSource(path)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                if (player === it) {
                    release()
                    onComplete()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                release()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    fun stop() = release()

    private fun release() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        if (originalVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            originalVolume = -1
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }
}
