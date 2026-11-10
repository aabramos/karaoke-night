package com.karaoketv

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Quiet background music for the lobby. Streams from the APK (no big buffers in memory),
 * loops seamlessly and fades in/out so it never cuts in harshly.
 */
class LobbyMusic(context: Context) {
    private val context = context.applicationContext   // never hold on to the screen
    private var player: MediaPlayer? = null
    private var fade: ValueAnimator? = null
    private var level = 0f
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("lobby_music", true)
        set(v) {
            prefs.edit().putBoolean("lobby_music", v).apply()
            if (v) start() else stop()
        }

    /** Fade in to [target] volume (0-1). The lobby default is deliberately low. */
    fun start(target: Float = LOBBY_VOLUME) {
        if (!enabled) return
        val p = player ?: runCatching {
            MediaPlayer.create(context, R.raw.lobby)?.apply {
                isLooping = true
                setVolume(0f, 0f)
            }
        }.getOrNull()?.also { player = it } ?: return
        if (!p.isPlaying) p.start()
        fadeTo(target, 1200) {}
    }

    /** Fade out and pause (kept prepared so it can come back instantly). */
    fun stop(ms: Long = 900) {
        val p = player ?: return
        fadeTo(0f, ms) { if (p.isPlaying) p.pause() }
    }

    fun release() {
        fade?.cancel()
        player?.release()
        player = null
    }

    private fun fadeTo(target: Float, ms: Long, done: () -> Unit) {
        fade?.cancel()
        val p = player ?: return
        fade = ValueAnimator.ofFloat(level, target).apply {
            duration = ms
            addUpdateListener {
                level = it.animatedValue as Float
                runCatching { p.setVolume(level, level) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) runCatching(done) }
            })
            start()
        }
    }

    companion object {
        const val LOBBY_VOLUME = 0.32f
    }
}

/** Short sound effects, decoded once into a small SoundPool. */
class Sfx(screen: Context) {
    private val context = screen.applicationContext
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val loaded = HashSet<Int>()
    private val pending = HashMap<Int, Float>()
    private val streams = HashMap<Int, Int>()

    val drumroll = pool.load(context, R.raw.drumroll, 1)
    val reveal = pool.load(context, R.raw.reveal, 1)
    val fanfare = pool.load(context, R.raw.fanfare, 1)
    val applause = pool.load(context, R.raw.applause, 1)
    val tick = pool.load(context, R.raw.tick, 1)
    val pop = pool.load(context, R.raw.pop, 1)

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            if (status == 0) {
                loaded += id
                pending.remove(id)?.let { vol -> streams[id] = p.play(id, vol, vol, 1, 0, 1f) }
            }
        }
    }

    fun play(sound: Int, volume: Float = 1f) {
        if (sound in loaded) {
            streams[sound] = pool.play(sound, volume, volume, 1, 0, 1f)
        } else {
            pending[sound] = volume
        }
    }

    fun stop(sound: Int) {
        pending.remove(sound)
        streams.remove(sound)?.let { pool.stop(it) }
    }

    fun release() = pool.release()
}
