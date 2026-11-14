package com.karaoketv

import android.content.Context
import org.json.JSONObject

/** One award of the night recap. [song]: the winner is a song, not a singer. */
data class Award(val icon: String, val title: String, val winner: String, val detail: String, val song: Boolean = false)

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

    /** Tonight's top three solo singers, each once with their best score (ties: see [RANKING]). */
    fun podium(entries: List<Entry>): List<Entry> = entries.filter { !it.duet }
        .sortedWith(RANKING)
        .distinctBy { it.name.lowercase() }
        .take(3)

    /**
     * The awards, in the order they're presented (the podium comes after them). Only awards that
     * mean something tonight are given: no "most songs" when everyone sang once.
     * [cheers]: phone reactions per singer; [unlocked]: achievements won tonight per singer;
     * [songCheers]: phone reactions per song.
     */
    fun awards(entries: List<Entry>, cheers: Map<String, Int>, unlocked: Map<String, Int>,
               songCheers: Map<String, Int> = emptyMap()): List<Award> {
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
            .maxByOrNull { l -> l.sumOf { it.score } / l.size }
            ?.let { l -> list += Award("🎯", "Steady voice", l.last().name, "Average of ${scoreText(Judge.tenth(l.sumOf { it.score } / l.size))} over ${l.size} songs") }

        // Achievement hunter
        unlocked.entries.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { (who, n) ->
            list += Award("🏅", "Badge hunter", who, "$n new achievements tonight")
        }

        // The best duet
        entries.filter { it.duet }.sortedWith(RANKING).firstOrNull()?.let {
            list += Award("💞", "Duet of the night", it.name, "${scoreText(it.score)} points with “${shortTitle(it.song).take(36)}”")
        }

        songOfTheNight(entries, songCheers)?.let { list += it }

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

    /**
     * 🎶 Song of the night: the one the phones cheered most (at least [SONG_CHEERS] cheers);
     * otherwise the best average score, once two or more songs have been sung.
     */
    fun songOfTheNight(entries: List<Entry>, songCheers: Map<String, Int>): Award? {
        fun singers(title: String) = entries.filter { shortTitle(it.song).equals(title, ignoreCase = true) }
            .sortedBy { it.time }.map { it.name }.distinctBy { it.lowercase() }
        fun by(names: List<String>) = when (names.size) {
            0 -> ""
            1 -> " · sung by ${names[0]}"
            else -> " · sung by ${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        }
        songCheers.entries.maxByOrNull { it.value }?.takeIf { it.value >= SONG_CHEERS }?.let { (title, n) ->
            return Award("🎶", "Song of the night", title.take(40), "$n cheers from the phones" + by(singers(title)).take(60), song = true)
        }
        if (entries.size < 2) return null
        val best = entries.groupBy { shortTitle(it.song).lowercase() }.values
            .maxWithOrNull(compareBy<List<Entry>> { l -> l.sumOf { it.score } / l.size }
                .thenBy { it.size }.thenByDescending { l -> l.minOf { it.time } }) ?: return null
        val title = shortTitle(best.first().song)
        val avg = scoreText(Judge.tenth(best.sumOf { it.score } / best.size))
        val detail = if (best.size == 1) "$avg points" + by(singers(title)) else "Average of $avg over ${best.size} performances"
        return Award("🎶", "Song of the night", title.take(40), detail, song = true)
    }

    /** Cheers a song needs to win Song of the night on cheers. */
    const val SONG_CHEERS = 5
}

/**
 * Phone reactions each singer and each song got tonight, for "Crowd favourite" and "Song of the
 * night". Tiny and short-lived: {"n": night number, "c": {"Ana": 12, …}, "s": {"Hey Jude": 9, …}};
 * a new night starts from zero.
 */
class Cheers(context: Context) {
    private val prefs = context.getSharedPreferences("cheers", Context.MODE_PRIVATE)
    private var night = -1L
    private val counts = LinkedHashMap<String, Int>()
    private val songs = LinkedHashMap<String, Int>()
    private var dirty = false

    init {
        runCatching {
            val o = JSONObject(prefs.getString("c", null) ?: "{}")
            night = o.optLong("n", -1)
            val c = o.optJSONObject("c")
            c?.keys()?.forEach { k -> counts[k] = c.optInt(k) }
            val s = o.optJSONObject("s")
            s?.keys()?.forEach { k -> songs[k] = s.optInt(k) }
        }
        rollOver()
    }

    private fun rollOver() {
        val now = Achievements.nightOf(System.currentTimeMillis())
        if (night != now) { night = now; counts.clear(); songs.clear(); dirty = true }
    }

    /** One more cheer for [singer] and their [song] (names and titles in any capitals count as one). */
    fun add(singer: String?, song: String? = null) {
        if (singer.isNullOrBlank()) return
        rollOver()
        bump(counts, singer)
        song?.let { shortTitle(it).take(60) }?.takeIf { it.isNotBlank() }?.let { bump(songs, it) }
        dirty = true
    }

    private fun bump(map: LinkedHashMap<String, Int>, name: String) {
        val key = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        if (key !in map && map.size >= MAX) return
        map[key] = (map[key] ?: 0) + 1
    }

    fun tonight(): Map<String, Int> { rollOver(); return counts.toMap() }

    /** Tonight's cheers per song (short titles). */
    fun songsTonight(): Map<String, Int> { rollOver(); return songs.toMap() }

    /** Called at quiet moments (after a song, when the app closes), not on every cheer. */
    fun save() {
        if (!dirty) return
        dirty = false
        val c = JSONObject(); counts.forEach { (k, v) -> c.put(k, v) }
        val s = JSONObject(); songs.forEach { (k, v) -> s.put(k, v) }
        prefs.edit().putString("c", JSONObject().put("n", night).put("c", c).put("s", s).toString()).apply()
    }

    private companion object { const val MAX = 150 }
}
