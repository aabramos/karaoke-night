package com.karaoketv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import kotlin.math.roundToInt

/**
 * The "judge". It doesn't listen: it's a weighted random number tuned so most performances
 * land between the high 70s and low 90s, and 95+ is a rare treat (about 1 song in 20).
 */
object Judge {
    private val rng = Random()

    /** Null when the singer stopped too early to deserve a score. */
    fun score(watchedFraction: Double): Int? {
        if (watchedFraction < 0.25) return null
        return if (watchedFraction >= 0.9) {
            (85 + 6 * rng.nextGaussian()).roundToInt().coerceIn(70, 100)
        } else {
            val base = 45 + 30 * ((watchedFraction - 0.25) / 0.65)
            (base + 5 * rng.nextGaussian()).roundToInt().coerceIn(35, 75)
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

data class Entry(val name: String, val score: Int, val song: String, val time: Long)

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
            list += Entry(o.optString("n"), o.optInt("s"), o.optString("song"), o.optLong("t"))
        }
        cached = list
        return list
    }

    fun tonight(): List<Entry> {
        val since = System.currentTimeMillis() - 12L * 3600_000
        return all().filter { it.time >= since }
    }

    fun top(entries: List<Entry>, n: Int = 10) =
        entries.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.time }).take(n)

    /** Saves the entry and returns its 1-based rank among tonight's scores. */
    @Synchronized
    fun add(entry: Entry): Int {
        val list = (all() + entry).sortedByDescending { it.score }.take(150).toMutableList()
        cached = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("s", it.score).put("song", it.song.take(100)).put("t", it.time)) }
        prefs.edit().putString("entries", arr.toString()).apply()
        return top(tonight(), Int.MAX_VALUE).indexOfFirst { it === entry } + 1
    }

    /** Names that have sung recently, most recent first. */
    fun recentNames(limit: Int = 8): List<String> =
        all().sortedByDescending { it.time }.map { it.name }.distinctBy { it.lowercase() }.take(limit)
}
