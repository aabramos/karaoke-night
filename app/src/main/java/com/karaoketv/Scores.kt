package com.karaoketv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

/** [duet]: sung by two people ("Ana & Rui"); duets have their own ranking. */
data class Entry(val name: String, val score: Int, val song: String, val time: Long, val duet: Boolean = false)

/** Scores saved on the Shield. "Tonight" = the last 12 hours. Loaded once and kept small. */
class Leaderboard(context: Context) {
    private val prefs = context.getSharedPreferences("leaderboard", Context.MODE_PRIVATE)
    private var cached: MutableList<Entry>? = null

    @Synchronized
    fun all(): List<Entry> {
        cached?.let { return it }
        val arr = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrElse { JSONArray() }
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += Entry(o.optString("n"), o.optInt("s"), o.optString("song"), o.optLong("t"), o.optBoolean("d"))
        }
        cached = list
        return list
    }

    /** Tonight's solo or duet scores. */
    fun tonight(duet: Boolean = false): List<Entry> {
        val since = System.currentTimeMillis() - 12L * 3600_000
        return all().filter { it.time >= since && it.duet == duet }
    }

    fun allTime(duet: Boolean = false): List<Entry> = all().filter { it.duet == duet }

    fun top(entries: List<Entry>, n: Int = 10) =
        entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.time }).take(n)

    /** Saves the entry and returns its 1-based rank among tonight's scores of the same kind (solo/duet). */
    @Synchronized
    fun add(entry: Entry): Int {
        // Keep the best 150 solo and 100 duet scores of all time, plus everything from tonight,
        // so tonight's boards and the rank below are always complete
        val since = System.currentTimeMillis() - 12L * 3600_000
        fun keep(duet: Boolean, n: Int) = (all() + entry).filter { it.duet == duet }.sortedByDescending { it.score }
            .filterIndexed { i, e -> i < n || e.time >= since }.take(300)
        saveEntries(keep(false, 150) + keep(true, 100))
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
        cached = list.toMutableList()
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("s", it.score).put("song", it.song.take(100)).put("t", it.time)
                .apply { if (it.duet) put("d", true) })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    /** People to offer first when picking a singer. */
    fun recentNames(limit: Int = 8): List<String> = players().take(limit)

    companion object {
        const val MAX_PLAYERS = 60
    }
}

/** How a duet's two names are joined everywhere: "Ana & Rui". */
const val DUET_JOIN = " & "
