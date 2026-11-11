package com.karaoketv

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Quiet background music for the lobby, faded in and out so it never cuts in harshly.
 * Plays through [SeamlessLoop] (no gap at the loop point); if this device can't decode that
 * way, it falls back to Android's MediaPlayer.
 */
class LobbyMusic(context: Context) {
    companion object {
        /**
         * Fixed, very quiet background level (about -28 dB): enough to fill the silence between
         * songs, never loud enough to annoy anyone. Deliberately not adjustable from the app.
         */
        const val LEVEL = 0.04f
    }

    private val context = context.applicationContext   // never hold on to the screen
    private var loop: SeamlessLoop? = null
    private var fallback: MediaPlayer? = null
    private var fade: ValueAnimator? = null
    private var level = 0f
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("lobby_music", true)
        set(v) {
            prefs.edit().putBoolean("lobby_music", v).apply()
            if (v) start() else stop()
        }

    /** Fade in over [fadeMs], always to the same very low level (it never follows the volume slider). */
    fun start(fadeMs: Long = 1200) {
        val target = LEVEL
        if (!enabled) return
        val fb = fallback
        if (fb != null) {
            if (!fb.isPlaying) fb.start()
        } else {
            val l = loop ?: SeamlessLoop(context, R.raw.lobby) {
                // Decoding failed on this device: switch to MediaPlayer (main thread)
                main.post { useFallback() }
            }.also { loop = it }
            l.setVolume(level)
            l.play()
        }
        fadeTo(target, fadeMs) {}
    }

    private fun useFallback() {
        val wasPlaying = loop?.isPlaying == true
        loop?.release()
        loop = null
        if (fallback != null) return
        fallback = runCatching { MediaPlayer.create(context, R.raw.lobby) }.getOrNull()?.apply {
            isLooping = true
            setVolume(level, level)
            if (wasPlaying) start()
        }
    }

    /** Fade out and pause (kept ready so it can come back instantly). */
    fun stop(ms: Long = 900) {
        if (loop == null && fallback == null) return
        fadeTo(0f, ms) {
            loop?.pause()
            fallback?.let { if (it.isPlaying) it.pause() }
        }
    }

    fun release() {
        fade?.cancel()
        main.removeCallbacksAndMessages(null)
        loop?.release()
        fallback?.release()
        loop = null
        fallback = null
    }

    private fun applyLevel() {
        loop?.setVolume(level)
        runCatching { fallback?.setVolume(level, level) }
    }

    private fun fadeTo(target: Float, ms: Long, done: () -> Unit) {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(level, target).apply {
            duration = ms
            addUpdateListener {
                level = it.animatedValue as Float
                applyLevel()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) runCatching(done) }
            })
            start()
        }
    }

}

/**
 * Plays a music file as an endless loop with no gap or click at the loop point.
 *
 * Players that loop by jumping back to the start leave a short silence there. Instead, this
 * decodes the file itself (MediaCodec) and feeds one continuous stream to an AudioTrack, cutting
 * from the loop end straight to the loop start inside that stream.
 *
 * The lobby file holds half a second of extra audio before and after the loop ([LEAD_S]), copied
 * from the loop itself. Loop points are counted in decoded frames, so whatever a decoder drops or
 * adds at the very start of a file shifts both points equally and the cut stays sample-exact.
 * Any other file (one without that layout) simply loops from its start to its end.
 *
 * One background thread; it sleeps while paused. Holds only the application context.
 */
class SeamlessLoop(context: Context, private val resId: Int, private val onFailure: () -> Unit) {
    private val context = context.applicationContext
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var paused = true
    @Volatile private var volume = 0f
    @Volatile private var track: android.media.AudioTrack? = null
    private var thread: Thread? = null

    val isPlaying get() = running && !paused

    fun play() {
        if (!running) return
        paused = false
        if (thread == null) thread = Thread({ runSafely() }, "lobby-music").apply { isDaemon = true; start() }
        runCatching { track?.play() }
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause() {
        paused = true
        runCatching { track?.pause() }
    }

    fun setVolume(v: Float) {
        volume = v
        runCatching { track?.setVolume(v) }
    }

    /** Stops the thread; it frees the decoder and audio output itself within a few milliseconds. */
    fun release() {
        running = false
        paused = false
        synchronized(lock) { lock.notifyAll() }
        thread = null
    }

    private fun runSafely() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            decodeLoop()
        } catch (e: Throwable) {
            if (running) onFailure()
        }
    }

    private fun decodeLoop() {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            context.resources.openRawResourceFd(resId).use { fd ->
                ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            val format = ex.getTrackFormat(0)
            ex.selectTrack(0)
            val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                format.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            val c = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var frameBytes = 4
            var loopStart = 0L
            var loopEnd = Long.MAX_VALUE
            var decoded = 0L            // frames decoded since the last jump to the start

            /** Opens the audio output and finds the loop points, from the decoder's output format. */
            fun prepare(out: android.media.MediaFormat) {
                val rate = out.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                val channels = out.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                frameBytes = channels * 2
                // The lobby file: LEAD_S + LOOP_S + LEAD_S long. Anything else loops whole.
                val expectedUs = ((2 * LEAD_S + LOOP_S) * 1_000_000).toLong()
                if (kotlin.math.abs(durationUs - expectedUs) < 60_000) {
                    loopStart = (LEAD_S * rate).toLong()
                    loopEnd = loopStart + (LOOP_S * rate).toLong()
                }
                if (track == null) track = openTrack(rate, channels)
            }

            while (running) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track == null) prepare(c.outputFormat)
                    continue
                }
                if (o < 0) continue
                // Some decoders never announce the format: read it from what they produce
                if (track == null) prepare(c.outputFormat)

                val buf = c.getOutputBuffer(o)!!
                val frames = info.size / frameBytes
                val first = decoded
                decoded += frames
                val from = maxOf(first, loopStart)
                val to = minOf(decoded, loopEnd)
                if (to > from && track != null) {
                    buf.position(info.offset + ((from - first) * frameBytes).toInt())
                    buf.limit(info.offset + ((to - first) * frameBytes).toInt())
                    writeAll(buf)
                }
                val end = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                c.releaseOutputBuffer(o, false)
                if (decoded >= loopEnd || end) {
                    // Jump back: the next decoded frame continues exactly where the loop starts
                    ex.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    c.flush()
                    decoded = 0
                    inputDone = false
                }
            }
        } finally {
            runCatching { track?.pause(); track?.flush() }
            runCatching { track?.release() }
            track = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    private fun openTrack(rate: Int, channels: Int): android.media.AudioTrack {
        val mask = if (channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
        val min = android.media.AudioTrack.getMinBufferSize(rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
        return android.media.AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // Half a second of audio: small, but enough that a busy moment never makes it run dry
            .setBufferSizeInBytes(maxOf(min * 2, rate * channels * 2 / 2))
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(volume)
                if (!paused) it.play()
            }
    }

    /** Writes without blocking, so pausing and releasing are never stuck behind a full buffer. */
    private fun writeAll(buf: java.nio.ByteBuffer) {
        val t = track ?: return
        while (running && buf.hasRemaining()) {
            if (paused) {
                synchronized(lock) { while (paused && running) lock.wait(250) }
                continue
            }
            val n = t.write(buf, buf.remaining(), android.media.AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) throw IllegalStateException("AudioTrack write failed: $n")
            if (n == 0) Thread.sleep(8)
        }
    }

    companion object {
        /** Layout of res/raw/lobby.ogg: LEAD_S of lead-in, the LOOP_S loop, LEAD_S of tail. */
        const val LEAD_S = 0.5
        const val LOOP_S = 60.0
    }
}

/** Short sound effects, decoded once into a small SoundPool. */
class Sfx(screen: Context) {
    private val context = screen.applicationContext
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    /** The party's one volume (0-1); each sound's own level is relative to it. */
    var master: Float = 1f

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
                pending.remove(id)?.let { vol -> streams[id] = p.play(id, vol, vol, priority(id), 0, 1f) }
            }
        }
    }

    /**
     * When too many sounds overlap, Android stops the lowest-priority one: the long applause,
     * fanfare and drumroll must never be cut off by a burst of short ticks and pops.
     */
    private fun priority(sound: Int) = when (sound) {
        applause, fanfare, drumroll -> 2
        reveal -> 1
        else -> 0
    }

    fun play(sound: Int, level: Float = 1f) {
        val volume = (level * master).coerceIn(0f, 1f)
        if (volume <= 0f) return                    // muted: don't use up a stream
        if (sound in loaded) {
            streams[sound] = pool.play(sound, volume, volume, priority(sound), 0, 1f)
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
