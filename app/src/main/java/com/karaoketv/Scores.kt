package com.karaoketv

import android.content.Context
import org.json.JSONArray
import java.util.Random
import kotlin.math.roundToInt

/**
 * The "judge". It doesn't listen: it's a weighted random number between 60 and 100, tuned so
 * most performances land between the mid 70s and low 90s, and 95+ is a rare treat.
 */
object Judge {
    private val rng = Random()
    const val MIN = 60

    /** Null when the singer stopped too early to deserve a score. */
    fun score(watchedFraction: Double): Int? {
        if (watchedFraction < 0.25) return null
        return if (watchedFraction >= 0.9) {
            (83 + 8 * rng.nextGaussian()).roundToInt().coerceIn(MIN, 100)
        } else {
            // Stopped early: a lower score, still never below MIN
            val base = 62 + 12 * ((watchedFraction - 0.25) / 0.65)
            (base + 4 * rng.nextGaussian()).roundToInt().coerceIn(MIN, 78)
        }
    }

    private val lines = listOf(
        100 to listOf("PERFECT!!!", "FLAWLESS!", "Is this the original?!"),
        95 to listOf("SUPERSTAR!", "STANDING OVATION!", "LEGENDARY!"),
        90 to listOf("AMAZING!", "What a voice!", "Goosebumps!"),
        85 to listOf("Great voice!", "Crowd pleaser!", "Nailed it!"),
        80 to listOf("Really good!", "Smooth!", "So much style!"),
        75 to listOf("Nice one!", "Solid!", "Rocking it!"),
        70 to listOf("Not bad!", "Brave effort!", "Fun times!"),
        60 to listOf("Warming up…", "Next one's yours!"),
        0 to listOf("Well… you tried! 😅", "The spirit was there!"),
    )

    fun verdict(score: Int): String = lines.first { score >= it.first }.second.let { it[rng.nextInt(it.size)] }

    fun stars(score: Int): Int = when {
        score >= 95 -> 5
        score >= 88 -> 4
        score >= 80 -> 3
        score >= 70 -> 2
        else -> 1
    }
}

/**
 * The moment's highlights shown under the rank: debut, personal best, hat-trick, comebacks…
 * [tonightBefore]: tonight's scores before this one. [songsBefore] and [bestBefore] (best solo
 * score, null if none) come from the singer's achievement record, which never forgets them.
 * At most [limit] are returned, the most special first.
 */
object Badges {
    fun earned(entry: Entry, tonightBefore: List<Entry>, songsBefore: Int, bestBefore: Int?, hour: Int, limit: Int = 2): List<String> {
        val tonight = tonightBefore.filter { it.duet == entry.duet && it.name.equals(entry.name, ignoreCase = true) }.sortedBy { it.time }
        val out = ArrayList<String>()
        if (entry.score >= 100) out += "💯 PERFECT SCORE"
        if (!entry.duet) {
            if (songsBefore == 0) out += "🎤 Debut performance!"
            else if (bestBefore != null) when {
                entry.score > bestBefore -> out += "📈 New personal best! (was $bestBefore)"
                entry.score == bestBefore -> out += "🎯 Matched your best ever"
            }
        }
        val last = tonight.lastOrNull()
        if (last != null && last.score < 75 && entry.score >= 85) out += "🚀 What a comeback!"
        if (entry.score >= 85 && tonight.size >= 2 && tonight.takeLast(2).all { it.score >= 85 }) out += "🔥 On fire: 3 great songs in a row"
        val count = tonight.size + 1
        if (count == 3) out += "🎩 Hat-trick: 3 songs tonight"
        else if (count >= 5 && count % 5 == 0) out += "🏃 $count songs tonight — marathon singer!"
        if (hour in 1..4) out += "🦉 Night owl"
        if (entry.duet && entry.score >= 90) out += "💞 Dream team"
        return out.take(limit)
    }
}

/** [duet]: sung by two people ("Ana & Rui"); duets have their own ranking. */
data class Entry(val name: String, val score: Int, val song: String, val time: Long, val duet: Boolean = false)

/**
 * Scores saved on the device, kept small: the all-time best [KEEP_SOLO] solo and [KEEP_DUETS] duet
 * scores (the hall of fame shows the top 8) plus everything from tonight, which tonight's board,
 * ranks and achievements need. Each score is one short row: ["Ana",91,1731360000,"Song title"]
 * (time in seconds; a 5th value 1 marks a duet). Loaded once, then kept in memory.
 */
class Leaderboard(context: Context) {
    private val prefs = context.getSharedPreferences("leaderboard", Context.MODE_PRIVATE)
    private var cached: List<Entry>? = null

    @Synchronized
    fun all(): List<Entry> {
        cached?.let { return it }
        val list = ArrayList<Entry>()
        val rows = prefs.getString("e", null)
        if (rows != null) {
            val arr = runCatching { JSONArray(rows) }.getOrElse { JSONArray() }
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                val name = r.optString(0)
                if (name.isEmpty()) continue
                list += Entry(name, r.optInt(1), r.optString(3), r.optLong(2) * 1000, r.optInt(4) == 1)
            }
        } else {
            // Versions before 3.12 saved one object per score, with field names and milliseconds
            val arr = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrElse { JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += Entry(o.optString("n"), o.optInt("s"), o.optString("song"), o.optLong("t"), o.optBoolean("d"))
            }
        }
        cached = list
        return list
    }

    /** Tonight's solo or duet scores. */
    fun tonight(duet: Boolean = false): List<Entry> {
        val since = System.currentTimeMillis() - TONIGHT_MS
        return all().filter { it.time >= since && it.duet == duet }
    }

    /** Everything from tonight, solo and duets (for achievements and highlights). */
    fun tonightAll(): List<Entry> {
        val since = System.currentTimeMillis() - TONIGHT_MS
        return all().filter { it.time >= since }
    }

    fun allTime(duet: Boolean = false): List<Entry> = all().filter { it.duet == duet }

    fun top(entries: List<Entry>, n: Int = 10) =
        entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.time }).take(n)

    /** Tonight's best [n] singers (or duets), each once, with their best score; ties go to who got there first. */
    fun tonightBest(n: Int, duet: Boolean = false): List<Entry> =
        top(tonight(duet), Int.MAX_VALUE).distinctBy { it.name.lowercase() }.take(n)

    /** Saves the entry and returns its 1-based rank among tonight's scores of the same kind (solo/duet). */
    @Synchronized
    fun add(entry: Entry): Int {
        val since = System.currentTimeMillis() - TONIGHT_MS
        fun keep(duet: Boolean, n: Int) = top((all() + entry).filter { it.duet == duet }, Int.MAX_VALUE)
            .filterIndexed { i, e -> i < n || e.time >= since }.take(MAX_TONIGHT)
        saveEntries(keep(false, KEEP_SOLO) + keep(true, KEEP_DUETS))
        return (top(tonight(entry.duet), Int.MAX_VALUE).indexOfFirst { it === entry } + 1).coerceAtLeast(1)
    }

    // ---- players: everyone who has been picked to sing (kept even before their first score) ----

    @Volatile private var playerCache: List<String>? = null

    /** All players, most recently active first (duet partners counted separately). */
    @Synchronized
    fun players(): List<String> {
        playerCache?.let { return it }
        val saved = runCatching { JSONArray(prefs.getString("players", "[]")) }.getOrElse { JSONArray() }
        val fromSaved = (0 until saved.length()).mapNotNull { cleanName(saved.optString(it)) }
        // Older versions didn't keep a list: add anyone with a score
        val fromScores = all().sortedByDescending { it.time }.flatMap { if (it.duet) it.name.split(DUET_JOIN) else listOf(it.name) }
        return (fromSaved + fromScores).distinctBy { it.lowercase() }.take(MAX_PLAYERS).also { playerCache = it }
    }

    /** Moves these players to the top of the list (adding them if they're new). */
    @Synchronized
    fun rememberPlayers(names: List<String>) {
        val clean = names.mapNotNull { cleanName(it) }
        if (clean.isEmpty()) return
        savePlayers((clean + players()).distinctBy { it.lowercase() }.take(MAX_PLAYERS))
    }

    /**
     * Removes a player and every score they're part of: their solo scores and any duet they sang
     * in. Returns false if there was no such player.
     */
    @Synchronized
    fun removePlayer(name: String): Boolean {
        val target = cleanName(name) ?: return false
        val before = players()
        if (before.none { it.equals(target, ignoreCase = true) }) return false
        savePlayers(before.filterNot { it.equals(target, ignoreCase = true) })
        val kept = all().filterNot { e ->
            if (e.duet) e.name.split(DUET_JOIN).any { it.equals(target, ignoreCase = true) }
            else e.name.equals(target, ignoreCase = true)
        }
        saveEntries(kept)
        return true
    }

    private fun savePlayers(list: List<String>) {
        playerCache = list
        prefs.edit().putString("players", JSONArray().also { a -> list.forEach { a.put(it) } }.toString()).apply()
    }

    private fun saveEntries(list: List<Entry>) {
        cached = list.toList()
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().put(it.name).put(it.score).put(it.time / 1000).put(it.song.take(SONG_CHARS))
                .apply { if (it.duet) put(1) })
        }
        prefs.edit().putString("e", arr.toString()).remove("entries").apply()
    }

    companion object {
        const val MAX_PLAYERS = 150
        const val KEEP_SOLO = 40
        const val KEEP_DUETS = 20
        /** A night can't hold more songs than this (about 4 minutes each, all night long). */
        const val MAX_TONIGHT = 250
        const val SONG_CHARS = 60
        const val TONIGHT_MS = 12L * 3600_000
    }
}

/** How a duet's two names are joined everywhere: "Ana & Rui". */
const val DUET_JOIN = " & "

/** A song that was sung, for "🕘 Played". */
data class Played(val song: Song, val singer: String, val time: Long)

/**
 * The last [MAX] different songs that were sung, most recent first. Survives restarts.
 * Saved as short rows: [video ID, title, channel, duration, singer, time in seconds].
 */
class History(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    @Volatile private var cached: List<Played>? = null

    @Synchronized
    fun all(): List<Played> {
        cached?.let { return it }
        val arr = runCatching { JSONArray(prefs.getString("p", null) ?: prefs.getString("played", "[]")) }.getOrElse { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i)
            val o = if (r == null) arr.optJSONObject(i) ?: return@mapNotNull null else null   // 3.10-3.11: objects
            val id = r?.optString(0) ?: o!!.optString("i")
            if (!isVideoId(id)) return@mapNotNull null
            if (r != null) Played(Song(id, r.optString(1).take(150), r.optString(2).take(80), r.optString(3).take(10)),
                r.optString(4).take(45), r.optLong(5) * 1000)
            else Played(Song(id, o!!.optString("t").take(150), o.optString("c").take(80), o.optString("d").take(10)),
                o.optString("s").take(45), o.optLong("w"))
        }
        cached = list
        return list
    }

    @Synchronized
    fun add(song: Song, singer: String) {
        val list = (listOf(Played(song, singer, System.currentTimeMillis())) + all().filter { it.song.id != song.id }).take(MAX)
        save(list)
    }

    /** Forgets a removed player's name on their songs (the songs stay, to sing again). */
    @Synchronized
    fun forgetSinger(name: String) {
        val people = { singer: String -> singer.split(DUET_JOIN) }
        if (all().none { p -> people(p.singer).any { it.equals(name, ignoreCase = true) } }) return
        save(all().map { p -> if (people(p.singer).any { it.equals(name, ignoreCase = true) }) p.copy(singer = "") else p })
    }

    private fun save(list: List<Played>) {
        cached = list
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().put(it.song.id).put(it.song.title.take(100)).put(it.song.channel.take(40))
                .put(it.song.duration).put(it.singer).put(it.time / 1000))
        }
        prefs.edit().putString("p", arr.toString()).remove("played").apply()
    }

    companion object {
        const val MAX = 10

        /** "🎤 Ana · 21:40" or "🎤 Ana · Tue" for older ones. */
        fun label(p: Played): String {
            val age = System.currentTimeMillis() - p.time
            val fmt = if (age < 20L * 3600_000) "HH:mm" else "EEE d MMM"
            val whenText = java.text.SimpleDateFormat(fmt, java.util.Locale.ENGLISH).format(java.util.Date(p.time))
            return if (p.singer.isEmpty()) "🕘 $whenText" else "🎤 ${p.singer}  ·  $whenText"
        }
    }
}
