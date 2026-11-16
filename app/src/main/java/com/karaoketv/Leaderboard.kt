package com.karaoketv

import android.content.Context
import org.json.JSONArray

/**
 * [duet]: sung by two people ("Ana & Rui"); duets have their own ranking. [score] has one decimal.
 * [room]: how much the room loved it (the phones' average vote times their say), which breaks ties.
 */
data class Entry(val name: String, val score: Double, val song: String, val time: Long, val duet: Boolean = false, val room: Double = 0.0)

/** How every list ranks scores: the higher score, then the one the room loved more, then who got there first. */
val RANKING: Comparator<Entry> = compareByDescending<Entry> { it.score }.thenByDescending { it.room }.thenBy { it.time }

/** A score as shown everywhere: one decimal ("87.4", "70.0"), and a perfect "100". */
fun scoreText(score: Double): String = if (score >= 100) "100" else String.format(java.util.Locale.US, "%.1f", score)

/**
 * Scores saved on the device, kept small: the all-time best [KEEP_SOLO] solo and [KEEP_DUETS] duet
 * scores (the hall of fame shows the top 8) plus everything from tonight, which tonight's board,
 * ranks and achievements need. Each score is one short row: ["Ana",91.4,1731360000,"Song title",0,0.84]
 * (time in seconds; then 1 for a duet, and the room's love for ties; older rows stop after the
 * title, or after the duet mark, and have whole-number scores). Loaded once, then kept in memory.
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
                list += Entry(name, r.optDouble(1, 0.0), r.optString(3), r.optLong(2) * 1000, r.optInt(4) == 1, r.optDouble(5, 0.0))
            }
        } else {
            // Versions before 3.12 saved one object per score, with field names and milliseconds
            val arr = runCatching { JSONArray(prefs.getString("entries", null) ?: "[]") }.getOrElse { JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += Entry(o.optString("n"), o.optInt("s").toDouble(), o.optString("song"), o.optLong("t"), o.optBoolean("d"))
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

    fun top(entries: List<Entry>, n: Int = 10) = entries.sortedWith(RANKING).take(n)

    /** Tonight's best [n] singers (or duets), each once, with their best score (ties: see [RANKING]). */
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
        val saved = runCatching { JSONArray(prefs.getString("players", null) ?: "[]") }.getOrElse { JSONArray() }
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
                .put(if (it.duet) 1 else 0).put(Math.round(it.room * 100) / 100.0))
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
