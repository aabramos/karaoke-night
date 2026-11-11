package com.karaoketv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Something a singer unlocks once and keeps. [how] tells people how to get it. */
data class Achievement(val id: String, val icon: String, val title: String, val how: String)

/**
 * What a singer has unlocked (achievement ID → the party night it was won, see [Achievements.nightOf]),
 * how many songs they've sung and their best solo score (null if none yet).
 */
data class SingerRecord(val name: String, val songs: Int, val best: Int?, val unlocked: Map<String, Long>, val lastNight: Long)

object Achievements {
    val ALL = listOf(
        Achievement("first", "🎤", "First song", "Sing your first song"),
        Achievement("s85", "🌟", "Rising star", "Score 85 or more"),
        Achievement("s90", "💎", "90 club", "Score 90 or more"),
        Achievement("s95", "🏅", "Showstopper", "Score 95 or more"),
        Achievement("s100", "💯", "Perfection", "Score a perfect 100"),
        Achievement("champ", "👑", "Champion", "Top the night's board (3+ singers)"),
        Achievement("pb", "📈", "Getting better", "Beat your own best score"),
        Achievement("comeback", "🚀", "Comeback", "85+ right after a score under 75"),
        Achievement("streak", "🔥", "On fire", "Three 85+ scores in a row in one night"),
        Achievement("hat", "🎩", "Hat-trick", "Sing 3 songs in one night"),
        Achievement("marathon", "🏃", "Marathon", "Sing 5 songs in one night"),
        Achievement("duet", "👫", "Duet partner", "Sing a duet"),
        Achievement("dream", "💞", "Dream team", "Score 90+ in a duet"),
        Achievement("owl", "🦉", "Night owl", "Sing between 1 and 5 am"),
        Achievement("regular", "🎙", "Regular", "Sing 10 songs"),
        Achievement("legend", "🏆", "Legend", "Sing 50 songs"),
        Achievement("nights", "🎉", "Party animal", "Sing at 3 different parties"),
    )
    private val byId = ALL.associateBy { it.id }
    fun get(id: String) = byId[id]

    /**
     * Every achievement this performance qualifies for (already unlocked ones included; the
     * book keeps only the new ones). [mineBefore]: this person's earlier scores, solo and duets.
     * [bestSoloBefore]: their best solo score so far, or null. [rank]: place among tonight's
     * scores of the same kind (0 = unknown). [soloSingersTonight]: different solo singers tonight.
     */
    fun earnedBy(
        entry: Entry, mineBefore: List<Entry>, bestSoloBefore: Int?, songsAfter: Int, nightsAfter: Int,
        rank: Int, soloSingersTonight: Int, hour: Int,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        val s = entry.score
        ids += "first"
        if (s >= 85) ids += "s85"
        if (s >= 90) ids += "s90"
        if (s >= 95) ids += "s95"
        if (s >= 100) ids += "s100"
        if (!entry.duet && rank == 1 && soloSingersTonight >= 3) ids += "champ"
        if (!entry.duet && bestSoloBefore != null && s > bestSoloBefore) ids += "pb"
        val tonight = mineBefore.filter { it.time >= entry.time - NIGHT_MS && it.time <= entry.time }.sortedBy { it.time }
        val last = tonight.lastOrNull()
        if (last != null && last.score < 75 && s >= 85) ids += "comeback"
        if (s >= 85 && tonight.size >= 2 && tonight.takeLast(2).all { it.score >= 85 }) ids += "streak"
        val songsTonight = tonight.size + 1
        if (songsTonight >= 3) ids += "hat"
        if (songsTonight >= 5) ids += "marathon"
        if (entry.duet) ids += "duet"
        if (entry.duet && s >= 90) ids += "dream"
        if (hour in 1..4) ids += "owl"
        if (songsAfter >= 10) ids += "regular"
        if (songsAfter >= 50) ids += "legend"
        if (nightsAfter >= 3) ids += "nights"
        return ids
    }

    /** The people in an entry: one singer, or both halves of a duet. */
    fun people(entry: Entry): List<String> = if (entry.duet) entry.name.split(DUET_JOIN) else listOf(entry.name)

    fun involves(entry: Entry, person: String) = people(entry).any { it.equals(person, ignoreCase = true) }

    /** Parties run past midnight: a "night" starts at 6 am, so 1 am still counts as the evening before. */
    fun nightOf(time: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        Math.floorDiv(time + zone.getOffset(time) - 6 * 3600_000L, 24 * 3600_000L)

    /** "11 Nov 2026" for a night number from [nightOf]. */
    fun dateOf(night: Long): String = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(night * 24 * 3600_000L + 12 * 3600_000L))

    const val NIGHT_MS = 12L * 3600_000
}

/**
 * Every singer's achievements, saved on the device and kept small: one short row per singer,
 * ["Ana", songs, best solo score, last party night, parties, {"s90": night, …}], where a "night"
 * is a day number (see [Achievements.nightOf]): when an achievement was won only needs the date.
 * At most [MAX] singers (the least recently active go first), the same limit as the players list.
 */
class AchievementBook(context: Context) {
    private val prefs = context.getSharedPreferences("achievements", Context.MODE_PRIVATE)

    private class Rec(var name: String, var songs: Int, var best: Int, var lastNight: Long, var nights: Int,
                      val unlocked: LinkedHashMap<String, Long>)

    private var cache: LinkedHashMap<String, Rec>? = null

    @Synchronized
    private fun recs(): LinkedHashMap<String, Rec> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Rec>()
        val rows = runCatching { JSONArray(prefs.getString("b", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val name = cleanName(r.optString(0)) ?: continue
            val u = LinkedHashMap<String, Long>()
            r.optJSONObject(5)?.let { uo -> uo.keys().forEach { id -> if (Achievements.get(id) != null) u[id] = uo.optLong(id) } }
            map[name.lowercase()] = Rec(name, r.optInt(1), r.optInt(2, -1), r.optLong(3), r.optInt(4), u)
        }
        // 3.11 kept one object per singer, with times in milliseconds
        if (rows.length() == 0) runCatching {
            val root = JSONObject(prefs.getString("book", "{}"))
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                val name = cleanName(o.optString("n")) ?: return@forEach
                val u = LinkedHashMap<String, Long>()
                o.optJSONObject("u")?.let { uo -> uo.keys().forEach { id -> if (Achievements.get(id) != null) u[id] = Achievements.nightOf(uo.optLong(id)) } }
                map[name.lowercase()] = Rec(name, o.optInt("c"), o.optInt("b", -1), o.optLong("ln"), o.optInt("nc"), u)
            }
        }
        cache = map
        return map
    }

    private fun save() {
        val map = recs()
        if (map.size > MAX) map.values.sortedBy { it.lastNight }.take(map.size - MAX).forEach { map.remove(it.name.lowercase()) }
        val rows = JSONArray()
        map.values.forEach { r ->
            val u = JSONObject(); r.unlocked.forEach { (id, night) -> u.put(id, night) }
            rows.put(JSONArray().put(r.name).put(r.songs).put(r.best).put(r.lastNight).put(r.nights).put(u))
        }
        prefs.edit().putString("b", rows.toString()).remove("book").apply()
    }

    private fun Rec.toRecord() = SingerRecord(name, songs, best.takeIf { it >= 0 }, LinkedHashMap(unlocked), lastNight)

    /**
     * Records a scored song and returns what each singer newly unlocked, in catalogue order.
     * [before]: the leaderboard before this entry was added.
     */
    @Synchronized
    fun record(entry: Entry, before: List<Entry>, rank: Int, hour: Int, persist: Boolean = true): List<Pair<String, Achievement>> {
        val out = ArrayList<Pair<String, Achievement>>()
        val since = entry.time - Achievements.NIGHT_MS
        val soloTonight = (before.filter { !it.duet && it.time >= since }.map { it.name.lowercase() } +
            (if (entry.duet) emptyList() else listOf(entry.name.lowercase()))).toSet().size
        val night = Achievements.nightOf(entry.time)
        for (person in Achievements.people(entry)) {
            val key = person.lowercase()
            val r = recs().getOrPut(key) { Rec(person, 0, -1, Long.MIN_VALUE, 0, LinkedHashMap()) }
            r.name = person
            r.songs++
            if (r.lastNight != night) { r.lastNight = night; r.nights++ }
            val mine = before.filter { Achievements.involves(it, person) }
            val ids = Achievements.earnedBy(entry, mine, r.best.takeIf { it >= 0 }, r.songs, r.nights, rank, soloTonight, hour)
            if (!entry.duet && entry.score > r.best) r.best = entry.score
            ids.forEach { id ->
                if (id !in r.unlocked) {
                    r.unlocked[id] = night
                    Achievements.get(id)?.let { out += person to it }
                }
            }
        }
        if (persist) save()
        return out
    }

    /** Everyone with at least one achievement, most achievements first. */
    @Synchronized
    fun singers(): List<SingerRecord> = recs().values
        .filter { it.unlocked.isNotEmpty() }
        .map { it.toRecord() }
        .sortedWith(compareByDescending<SingerRecord> { it.unlocked.size }.thenByDescending { it.lastNight })

    @Synchronized
    fun of(name: String): SingerRecord? = recs()[name.lowercase()]?.toRecord()

    @Synchronized
    fun remove(name: String) {
        if (recs().remove(name.lowercase()) != null) save()
    }

    /**
     * Once, when this version first runs: awards achievements for the scores already saved, so
     * regulars don't start from zero. ("Champion" can't be worked out afterwards.)
     */
    @Synchronized
    fun backfill(entries: List<Entry>) {
        if (prefs.getBoolean("backfilled", false)) return
        val done = ArrayList<Entry>()
        val cal = java.util.Calendar.getInstance()
        entries.sortedBy { it.time }.forEach { e ->
            cal.timeInMillis = e.time
            record(e, done, rank = 0, hour = cal.get(java.util.Calendar.HOUR_OF_DAY), persist = false)
            done += e
        }
        save()
        prefs.edit().putBoolean("backfilled", true).apply()
    }

    companion object {
        const val MAX = Leaderboard.MAX_PLAYERS
    }
}
