package com.karaoketv

import android.content.Context
import org.json.JSONArray

/** A song that was sung, for "🕘 Played". */
data class Played(val song: Song, val singer: String, val time: Long)

/** One song sung tonight (each time it was sung, unlike [Played]): for the fair queue's turns. */
data class Sung(val id: String, val singer: String, val time: Long)

/**
 * The last [MAX] different songs that were sung, most recent first, plus a tiny log of every
 * song sung in the last [Leaderboard.TONIGHT_MS] (who and which video, for fair turns and for not
 * repeating songs). Survives restarts.
 * Saved as short rows: [video ID, title, channel, duration, singer, time in seconds], and the
 * night log as [video ID, singer, time in seconds].
 */
class History(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    @Volatile private var cached: List<Played>? = null
    @Volatile private var night: List<Sung>? = null

    @Synchronized
    fun all(): List<Played> {
        cached?.let { return it }
        val arr = runCatching { JSONArray(prefs.getString("p", null) ?: "[]") }.getOrElse { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i) ?: return@mapNotNull null
            val id = r.optString(0)
            if (!isVideoId(id)) return@mapNotNull null
            Played(Song(id, r.optString(1).take(150), r.optString(2).take(80), r.optString(3).take(10)),
                r.optString(4).take(45), r.optLong(5) * 1000)
        }
        cached = list
        return list
    }

    /** Every song sung tonight, oldest first (called from the phones' threads too). */
    @Synchronized
    fun tonight(): List<Sung> {
        val since = System.currentTimeMillis() - Leaderboard.TONIGHT_MS
        night?.let { list -> return list.filter { it.time >= since } }
        val arr = runCatching { JSONArray(prefs.getString("n", null) ?: "[]") }.getOrElse { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i) ?: return@mapNotNull null
            Sung(r.optString(0).takeIf(::isVideoId) ?: return@mapNotNull null, r.optString(1).take(45), r.optLong(2) * 1000)
        }
        night = list
        return list.filter { it.time >= since }
    }

    @Synchronized
    fun add(song: Song, singer: String) {
        val now = System.currentTimeMillis()
        val list = (listOf(Played(song, singer, now)) + all().filter { it.song.id != song.id }).take(MAX)
        save(list, (tonight() + Sung(song.id, singer, now)).takeLast(NIGHT_MAX))
    }

    /** Forgets a removed player's name on their songs (the songs stay, to sing again). */
    @Synchronized
    fun forgetSinger(name: String) {
        val people = { singer: String -> singer.split(DUET_JOIN) }
        val theirs = { singer: String -> people(singer).any { it.equals(name, ignoreCase = true) } }
        if (all().none { theirs(it.singer) } && tonight().none { theirs(it.singer) }) return
        save(all().map { if (theirs(it.singer)) it.copy(singer = "") else it },
            tonight().map { if (theirs(it.singer)) it.copy(singer = "") else it })
    }

    private fun save(list: List<Played>, sung: List<Sung>) {
        cached = list
        night = sung
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().put(it.song.id).put(it.song.title.take(100)).put(it.song.channel.take(40))
                .put(it.song.duration).put(it.singer).put(it.time / 1000))
        }
        val log = JSONArray()
        sung.forEach { log.put(JSONArray().put(it.id).put(it.singer).put(it.time / 1000)) }
        prefs.edit().putString("p", arr.toString()).putString("n", log.toString()).remove("played").apply()
    }

    companion object {
        const val MAX = 10
        /** The night log's cap (a very long party); older songs drop off after 12 hours anyway. */
        const val NIGHT_MAX = 150

        /** "🎤 Ana · 21:40" or "🎤 Ana · Tue" for older ones. */
        fun label(p: Played): String {
            val age = System.currentTimeMillis() - p.time
            val fmt = if (age < 20L * 3600_000) "HH:mm" else "EEE d MMM"
            val whenText = java.text.SimpleDateFormat(fmt, java.util.Locale.ENGLISH).format(java.util.Date(p.time))
            return if (p.singer.isEmpty()) "🕘 $whenText" else "🎤 ${p.singer}  ·  $whenText"
        }
    }
}
