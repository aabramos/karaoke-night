package com.karaoketv

import android.content.Context
import org.json.JSONObject

/** One award of the night recap. */
data class Award(val icon: String, val title: String, val winner: String, val detail: String)

/** The numbers shown at the start of the recap. */
data class NightStats(val songs: Int, val singers: Int, val duets: Int, val cheers: Int)

/**
 * "🌙 Close the night": tonight's awards, worked out from the scores already saved. Nothing is
 * changed or deleted; the recap can be watched as often as people like.
 */
object Recap {

    /** Everyone in an entry: one singer, or both halves of a duet. */
    private fun people(e: Entry): List<String> = if (e.duet) e.name.split(DUET_JOIN) else listOf(e.name)

    fun stats(entries: List<Entry>, cheers: Map<String, Int>): NightStats = NightStats(
        songs = entries.size,
        singers = entries.flatMap(::people).map { it.lowercase() }.distinct().size,
        duets = entries.count { it.duet },
        cheers = cheers.values.sum(),
    )

    /** Tonight's top three solo singers, each once with their best score (first to get there wins a tie). */
    fun podium(entries: List<Entry>): List<Entry> = entries.filter { !it.duet }
        .sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.time })
        .distinctBy { it.name.lowercase() }
        .take(3)

    /**
     * The awards, in the order they're presented (the podium comes after them). Only awards that
     * mean something tonight are given: no "most songs" when everyone sang once.
     * [cheers]: phone reactions per singer; [unlocked]: achievements won tonight per singer.
     */
    fun awards(entries: List<Entry>, cheers: Map<String, Int>, unlocked: Map<String, Int>): List<Award> {
        if (entries.isEmpty()) return emptyList()
        val list = ArrayList<Award>()
        val byTime = entries.sortedBy { it.time }
        val solo = entries.filter { !it.duet }
        val champion = podium(entries).firstOrNull()?.name

        // Who broke the ice
        byTime.first().let { list += Award("🚪", "Opening act", it.name, "Broke the ice with “${shortTitle(it.song).take(40)}”") }

        // Most songs (a duet counts for both singers); ties go to whoever got there first
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (e in byTime) for (p in people(e)) {
            val k = p.lowercase()
            counts[k] = p to ((counts[k]?.second ?: 0) + 1)
        }
        counts.values.maxByOrNull { it.second }?.takeIf { it.second >= 3 }?.let { (who, n) ->
            if (counts.values.count { it.second == n } == 1) list += Award("🏃", "Marathon singer", who, "$n songs tonight. Legend!")
        }

        // Most phone reactions
        cheers.entries.maxByOrNull { it.value }?.takeIf { it.value >= 5 }?.let { (who, n) ->
            list += Award("📣", "Crowd favourite", who, "$n cheers from the phones")
        }

        // Steadiest voice: best average over 3+ solo songs (not just one lucky score)
        solo.groupBy { it.name.lowercase() }.values.filter { it.size >= 3 }
            .maxByOrNull { l -> l.sumOf { it.score }.toDouble() / l.size }
            ?.let { l -> list += Award("🎯", "Steady voice", l.last().name, "Average of ${Math.round(l.sumOf { it.score }.toDouble() / l.size)} over ${l.size} songs") }

        // Achievement hunter
        unlocked.entries.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { (who, n) ->
            list += Award("🏅", "Badge hunter", who, "$n new achievements tonight")
        }

        // The best duet
        entries.filter { it.duet }.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.time }).firstOrNull()?.let {
            list += Award("💞", "Duet of the night", it.name, "${it.score} points with “${shortTitle(it.song).take(36)}”")
        }

        // A warm one for the lowest score of a busy night (never the score itself, and never the champion)
        if (solo.size >= 5) {
            solo.minByOrNull { it.score }?.takeIf { it.score < 75 && !it.name.equals(champion, ignoreCase = true) }?.let {
                list += Award("🦁", "Bravest singer", it.name, "Sang with all their heart ❤️")
            }
        }

        // Who closed the show
        if (byTime.size >= 2) byTime.last().let {
            list += Award("🦉", "Closing act", it.name, "Closed the show with “${shortTitle(it.song).take(40)}”")
        }
        return list
    }
}

/**
 * Phone reactions each singer got tonight, for the "Crowd favourite" award. Tiny and short-lived:
 * {"n": night number, "c": {"Ana": 12, ...}}; a new night starts from zero.
 */
class Cheers(context: Context) {
    private val prefs = context.getSharedPreferences("cheers", Context.MODE_PRIVATE)
    private var night = -1L
    private val counts = LinkedHashMap<String, Int>()
    private var dirty = false

    init {
        runCatching {
            val o = JSONObject(prefs.getString("c", null) ?: "{}")
            night = o.optLong("n", -1)
            val c = o.optJSONObject("c")
            c?.keys()?.forEach { k -> counts[k] = c.optInt(k) }
        }
        rollOver()
    }

    private fun rollOver() {
        val now = Achievements.nightOf(System.currentTimeMillis())
        if (night != now) { night = now; counts.clear(); dirty = true }
    }

    /** One more cheer for [singer] (the same name in any capitals counts as one person). */
    fun add(singer: String?) {
        if (singer.isNullOrBlank()) return
        rollOver()
        val key = counts.keys.firstOrNull { it.equals(singer, ignoreCase = true) } ?: singer
        if (key !in counts && counts.size >= MAX) return
        counts[key] = (counts[key] ?: 0) + 1
        dirty = true
    }

    fun tonight(): Map<String, Int> { rollOver(); return counts.toMap() }

    /** Called at quiet moments (after a song, when the app closes), not on every cheer. */
    fun save() {
        if (!dirty) return
        dirty = false
        val c = JSONObject()
        counts.forEach { (k, v) -> c.put(k, v) }
        prefs.edit().putString("c", JSONObject().put("n", night).put("c", c).toString()).apply()
    }

    private companion object { const val MAX = 150 }
}
