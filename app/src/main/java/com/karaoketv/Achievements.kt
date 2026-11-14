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
/**
 * What's saved about a singer. [streak]: parties in a row they sang at, up to their last one
 * ([streakAlive] says whether that run is still going); [bestStreak]: their longest run.
 */
data class SingerRecord(
    val name: String, val songs: Int, val best: Double?, val unlocked: Map<String, Long>, val lastNight: Long,
    val nights: Int = 0, val streak: Int = 0, val bestStreak: Int = 0, val streakAlive: Boolean = false,
)

object Achievements {
    /** Achievement names and descriptions are kept short so they fit their tiles on the TV. */
    const val MAX_TITLE = 16
    const val MAX_HOW = 25

    /** The main achievements. Score ones say "points" in their name, so nobody reads "90" as the 1990s. */
    val MAIN = listOf(
        Achievement("first", "🎤", "First song", "Sing your first song"),
        Achievement("s85", "🌟", "85 points", "Score 85 points or more"),
        Achievement("s90", "💎", "90 points", "Score 90 points or more"),
        Achievement("s95", "🏅", "95 points", "Score 95 points or more"),
        Achievement("s100", "💯", "Perfect 100", "Score a perfect 100"),
        Achievement("champ", "👑", "Champion", "Be #1 with 3+ singers"),
        Achievement("pb", "📈", "Getting better", "Beat your own best score"),
        Achievement("comeback", "🚀", "Comeback", "85+ after a score < 75"),
        Achievement("streak", "🔥", "On fire", "Three 85+ scores in a row"),
        Achievement("hat", "🎩", "Hat-trick", "Sing 3 songs in one night"),
        Achievement("marathon", "🏃", "Marathon", "Sing 5 songs in one night"),
        Achievement("unstoppable", "💪", "Unstoppable", "Sing 10 songs in a night"),
        Achievement("duet", "👫", "Duet partner", "Sing a duet"),
        Achievement("dream", "💞", "Dream team", "Score 90+ in a duet"),
        Achievement("harmony", "🎶", "Perfect harmony", "Score 95+ in a duet"),
        Achievement("bff", "👯", "Best friends", "3 duets with one partner"),
        Achievement("butterfly", "🦋", "Social butterfly", "Duets with 3 people"),
        Achievement("encore", "🔁", "Encore!", "Same song twice a night"),
        Achievement("lucky", "🎲", "Lucky 77", "Score 77.0 to 77.9"),
        Achievement("stagefright", "🙈", "Stage fright", "Stop a song early"),
        Achievement("tomato", "🍅", "Tomato magnet", "Score under 40 points"),
        Achievement("owl", "🦉", "Night owl", "Sing between 1 and 5 am"),
        Achievement("regular", "🎙", "Regular", "Sing 10 songs"),
        Achievement("legend", "🏆", "Legend", "Sing 50 songs"),
        Achievement("nights", "🎉", "Party animal", "Sing at 3 parties"),
        Achievement("infinity", "♾️", "Infinity Win", "Score 95+ in 5 songs"),
        Achievement("sixseven", "🤲", "Six Seven!", "Score 67.0 to 67.9"),
        Achievement("stinky", "💩", "Stinky Poop", "Score under 10 points"),
        Achievement("huh", "🤨", "Huh?!", "Skip at the very end"),
        Achievement("skip10", "🙅", "No", "Skip 10 songs"),
        Achievement("c5", "👋", "Noticed", "Get 5 emojis from phones"),
        Achievement("c10", "🙌", "Crowd pleaser", "Get 10 emojis from phones"),
        Achievement("c20", "📣", "Fan club", "Get 20 emojis from phones"),
        Achievement("c50", "🏟️", "Stadium star", "Get 50 emojis from phones"),
        Achievement("c100", "🌠", "Superstar", "Get 100 phone emojis"),
        Achievement("lover", "💘", "Lover", "Get 30 😍 from phones"),
        // Special dates (the party's date: 1 am still counts as the evening before, see nightOf)
        Achievement("xmas", "🎄", "Christmas carol", "Sing on Dec 24 or 25"),
        Achievement("halloween", "🎃", "Spooky singer", "Sing on Halloween"),
        Achievement("nye", "🎆", "New Year's Eve", "Sing on December 31"),
        Achievement("valentine", "💝", "Be my Valentine", "Sing on February 14"),
        Achievement("carnival", "🎊", "Carnival", "Sing during Carnival"),
        Achievement("patrick", "☘️", "St Patrick's", "Sing on March 17"),
        Achievement("easter", "🐣", "Easter bunny", "Sing on Easter weekend"),
        Achievement("musicday", "🎷", "Music Day", "Sing on June 21"),
        Achievement("friday13", "🔪", "Friday the 13th", "Sing on a Friday the 13th"),
    )
    /** One per genre: the first time someone sings it (see [Genres]). */
    val GENRES = Genres.ALL.map { Achievement(genreId(it.id), it.icon, it.title, it.how) }
    val ALL = MAIN + GENRES
    fun genreId(genre: String) = "g_$genre"
    private val byId = ALL.associateBy { it.id }
    fun get(id: String) = byId[id]

    /** Both singers of a duet, in a fixed order, to recognise the same pair in any order. */
    private fun pair(entry: Entry) = people(entry).map { it.lowercase() }.sorted()

    /**
     * Every achievement this performance qualifies for (already unlocked ones included; the
     * book keeps only the new ones). [mineBefore]: this person's earlier scores, solo and duets.
     * [bestSoloBefore]: their best solo score so far, or null. [rank]: place among tonight's
     * scores of the same kind (0 = unknown). [soloSingersTonight]: different solo singers tonight.
     * [person]: whose achievements these are (for duets: which of the two). [stoppedEarly]: the
     * song was stopped before the end. [emojis], [hearts]: phone emojis this person has received
     * in total (all of them, and 😍 only), counted as they arrive.
     */
    fun earnedBy(
        entry: Entry, mineBefore: List<Entry>, bestSoloBefore: Double?, songsAfter: Int, nightsAfter: Int,
        rank: Int, soloSingersTonight: Int, hour: Int, genres: List<String> = emptyList(),
        person: String = entry.name, stoppedEarly: Boolean = false, emojis: Int = 0, hearts: Int = 0,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        val s = entry.score
        ids += "first"
        if (s >= 85) ids += "s85"
        if (s >= 90) ids += "s90"
        if (s >= 95) ids += "s95"
        if (s >= 100) ids += "s100"
        if (s.toInt() == 77) ids += "lucky"                 // 77.0 … 77.9
        if (s < 40) ids += "tomato"
        if (s < 10) ids += "stinky"
        if (s.toInt() == 67) ids += "sixseven"
        if (s >= 95 && mineBefore.count { it.score >= 95 } + 1 >= 5) ids += "infinity"
        if (stoppedEarly) ids += "stagefright"
        if (!entry.duet && rank == 1 && soloSingersTonight >= 3) ids += "champ"
        if (!entry.duet && bestSoloBefore != null && s > bestSoloBefore) ids += "pb"
        val tonight = mineBefore.filter { it.time >= entry.time - NIGHT_MS && it.time <= entry.time }.sortedBy { it.time }
        val last = tonight.lastOrNull()
        if (last != null && last.score < 75 && s >= 85) ids += "comeback"
        if (s >= 85 && tonight.size >= 2 && tonight.takeLast(2).all { it.score >= 85 }) ids += "streak"
        val songsTonight = tonight.size + 1
        if (songsTonight >= 3) ids += "hat"
        if (songsTonight >= 5) ids += "marathon"
        if (songsTonight >= 10) ids += "unstoppable"
        val song = shortTitle(entry.song).lowercase()
        if (song.isNotEmpty() && tonight.any { shortTitle(it.song).lowercase() == song }) ids += "encore"
        if (entry.duet) {
            ids += "duet"
            if (s >= 90) ids += "dream"
            if (s >= 95) ids += "harmony"
            val duetsTonight = tonight.filter { it.duet }
            if (duetsTonight.count { pair(it) == pair(entry) } + 1 >= 3) ids += "bff"
            val me = person.lowercase()
            val partners = (duetsTonight + entry).flatMap { pair(it) }.filter { it != me }.toSet()
            if (partners.size >= 3) ids += "butterfly"
        }
        if (hour in 1..4) ids += "owl"
        if (songsAfter >= 10) ids += "regular"
        if (songsAfter >= 50) ids += "legend"
        if (nightsAfter >= 3) ids += "nights"
        genres.forEach { g -> if (Genres.get(g) != null) ids += genreId(g) }
        ids += forEmojis(emojis, hearts)
        ids += specialDays(nightOf(entry.time))
        return ids
    }

    /** The emoji achievements for this many emojis received (and this many 😍). */
    fun forEmojis(emojis: Int, hearts: Int): Set<String> {
        val ids = LinkedHashSet<String>()
        if (emojis >= 5) ids += "c5"
        if (emojis >= 10) ids += "c10"
        if (emojis >= 20) ids += "c20"
        if (emojis >= 50) ids += "c50"
        if (emojis >= 100) ids += "c100"
        if (hearts >= 30) ids += "lover"
        return ids
    }

    /**
     * The special-date achievements for a party night (a night number from [nightOf], so a party
     * that runs past midnight keeps its date). Carnival (Saturday to Tuesday) and Easter weekend
     * move every year and are worked out from the date of Easter.
     */
    fun specialDays(night: Long): Set<String> {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = night * DAY_MS + DAY_MS / 2
        val md = (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        val ids = LinkedHashSet<String>()
        when (md) {
            1224, 1225 -> ids += "xmas"
            1031 -> ids += "halloween"
            1231 -> ids += "nye"
            214 -> ids += "valentine"
            317 -> ids += "patrick"
            621 -> ids += "musicday"
        }
        if (md % 100 == 13 && c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY) ids += "friday13"
        val easter = easterNight(c.get(java.util.Calendar.YEAR))
        if (night in easter - 50..easter - 47) ids += "carnival"     // Saturday to Shrove Tuesday
        if (night in easter - 1..easter) ids += "easter"             // Saturday and Sunday
        return ids
    }

    /** Easter Sunday of [year] as a day number (the anonymous Gregorian algorithm). */
    fun easterNight(year: Int): Long {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear(); cal.set(year, month - 1, day)
        return Math.floorDiv(cal.timeInMillis, DAY_MS)
    }

    private const val DAY_MS = 24 * 3600_000L

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
 * ["Ana", songs, best solo score, last party night, parties, {"s90": night, …}, emojis, 😍, skips,
 * streak, best streak, songs rated by the crowd, their average vote × 100], where a "night"
 * is a day number (see [Achievements.nightOf]): when an achievement was won only needs the date.
 * At most [MAX] singers (the least recently active go first), the same limit as the players list.
 */
class AchievementBook(context: Context) {
    private val prefs = context.getSharedPreferences("achievements", Context.MODE_PRIVATE)

    private class Rec(var name: String, var songs: Int, var best: Double, var lastNight: Long, var nights: Int,
                      val unlocked: LinkedHashMap<String, Long>, var emojis: Int = 0, var hearts: Int = 0,
                      var skips: Int = 0, var streak: Int = 0, var bestStreak: Int = 0,
                      var rated: Int = 0, var crowdAvg: Int = 0)

    /** The last two party nights (any singer), to tell whether someone sang at the party before this one. */
    private var partyPrev = Long.MIN_VALUE
    private var partyCur = Long.MIN_VALUE

    private var cache: LinkedHashMap<String, Rec>? = null

    @Synchronized
    private fun recs(): LinkedHashMap<String, Rec> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Rec>()
        val rows = runCatching { JSONArray(prefs.getString("b", null) ?: "[]") }.getOrElse { JSONArray() }
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val name = cleanName(r.optString(0)) ?: continue
            val u = LinkedHashMap<String, Long>()
            r.optJSONObject(5)?.let { uo -> uo.keys().forEach { id -> if (Achievements.get(id) != null) u[id] = uo.optLong(id) } }
            map[name.lowercase()] = Rec(name, r.optInt(1), r.optDouble(2, -1.0), r.optLong(3), r.optInt(4), u, r.optInt(6), r.optInt(7),
                r.optInt(8), r.optInt(9), r.optInt(10), r.optInt(11).coerceIn(0, CAP), r.optInt(12).coerceIn(-200, 200))
        }
        prefs.getString("pn", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 }?.let { (p, c) -> partyPrev = p; partyCur = c }
        // 3.11 kept one object per singer, with times in milliseconds
        if (rows.length() == 0) runCatching {
            val root = JSONObject(prefs.getString("book", null) ?: "{}")
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                val name = cleanName(o.optString("n")) ?: return@forEach
                val u = LinkedHashMap<String, Long>()
                o.optJSONObject("u")?.let { uo -> uo.keys().forEach { id -> if (Achievements.get(id) != null) u[id] = Achievements.nightOf(uo.optLong(id)) } }
                map[name.lowercase()] = Rec(name, o.optInt("c"), o.optInt("b", -1).toDouble(), o.optLong("ln"), o.optInt("nc"), u)
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
            rows.put(JSONArray().put(r.name).put(r.songs).put(r.best).put(r.lastNight).put(r.nights).put(u).put(r.emojis).put(r.hearts)
                .put(r.skips).put(r.streak).put(r.bestStreak).put(r.rated).put(r.crowdAvg))
        }
        prefs.edit().putString("b", rows.toString()).putString("pn", "$partyPrev,$partyCur").remove("book").apply()
        dirty = false
    }

    /** Emojis counted since the last save (saved after each song, not on every emoji). */
    private var dirty = false

    /**
     * A phone sent [emoji] to [singer] (a duet counts for both). Only counted here; the emoji
     * achievements are given at the end of the song (see [record] and [unlockNow]).
     */
    @Synchronized
    fun cheered(singer: String?, emoji: String) {
        if (singer.isNullOrBlank()) return
        for (person in singer.split(DUET_JOIN)) {
            val key = person.lowercase()
            if (key !in recs() && recs().size >= MAX * 2) continue
            val r = recs().getOrPut(key) { Rec(person, 0, -1.0, Long.MIN_VALUE, 0, LinkedHashMap()) }
            if (r.emojis < CAP) r.emojis++
            if (emoji == HEART && r.hearts < CAP) r.hearts++
            dirty = true
        }
    }

    /**
     * For a song that ended without a score (skipped): the emoji achievements the singer(s) have
     * now, plus [extra] (e.g. "huh"). [skipped] counts towards "No" (skip [SKIPS] songs).
     * Returns what's new, like [record].
     */
    @Synchronized
    fun unlockNow(singer: String, extra: Set<String> = emptySet(), skipped: Boolean = false): List<Pair<String, Achievement>> {
        val out = ArrayList<Pair<String, Achievement>>()
        val night = Achievements.nightOf(System.currentTimeMillis())
        for (person in singer.split(DUET_JOIN)) {
            val r = recs().getOrPut(person.lowercase()) { Rec(person, 0, -1.0, Long.MIN_VALUE, 0, LinkedHashMap()) }
            if (skipped && r.skips < CAP) { r.skips++; dirty = true }
            val more = if (r.skips >= SKIPS) extra + "skip10" else extra
            (Achievements.forEmojis(r.emojis, r.hearts) + more).forEach { id ->
                if (id !in r.unlocked) {
                    r.unlocked[id] = night
                    Achievements.get(id)?.let { out += person to it }
                }
            }
        }
        if (out.isNotEmpty() || dirty) save()
        return out
    }

    /**
     * The singer's reputation in points (see [Judge.reputation]): how past crowds voted for them.
     * A duet gets the average of both singers'.
     */
    @Synchronized
    fun reputation(singer: String): Double = singer.split(DUET_JOIN)
        .map { p -> recs()[p.lowercase()]?.let { Judge.reputation(it.crowdAvg / 100.0, it.rated) } ?: 0.0 }
        .average().takeIf { !it.isNaN() } ?: 0.0

    /**
     * The crowd rated [singer]'s song: [average] vote (-2 … +2). Recent songs weigh most: a plain
     * average over the first [REP_WINDOW] songs, then each new one counts for 1/[REP_WINDOW].
     * Saved with the song's other results (see [record]).
     */
    @Synchronized
    fun rated(singer: String, average: Double) {
        for (person in singer.split(DUET_JOIN)) {
            val key = person.lowercase()
            if (key !in recs() && recs().size >= MAX * 2) continue
            val r = recs().getOrPut(key) { Rec(person, 0, -1.0, Long.MIN_VALUE, 0, LinkedHashMap()) }
            r.rated = (r.rated + 1).coerceAtMost(CAP)
            val target = (average.coerceIn(-2.0, 2.0) * 100)
            r.crowdAvg = (r.crowdAvg + (target - r.crowdAvg) / minOf(r.rated, REP_WINDOW)).let { Math.round(it).toInt() }.coerceIn(-200, 200)
            dirty = true
        }
    }

    /** Saves counted emojis (when the app goes to the background). */
    @Synchronized
    fun flush() { if (dirty) save() }

    private fun Rec.toRecord() = SingerRecord(name, songs, best.takeIf { it >= 0 }, LinkedHashMap(unlocked), lastNight,
        nights, streak, bestStreak, streakAlive = lastNight == partyCur ||
            (lastNight == partyPrev && partyCur == Achievements.nightOf(System.currentTimeMillis())))   // tonight's party: not their turn yet

    /** A new party night: remember it, so the next party can tell who came to this one. */
    private fun markParty(night: Long) {
        if (night > partyCur) { partyPrev = partyCur; partyCur = night }
    }

    /** The party before [night] (any singer's), or MIN_VALUE if none is known. */
    private fun partyBefore(night: Long) = if (night == partyCur) partyPrev else partyCur

    /**
     * Records a scored song and returns what each singer newly unlocked, in catalogue order.
     * [before]: the leaderboard before this entry was added.
     */
    @Synchronized
    fun record(entry: Entry, before: List<Entry>, rank: Int, hour: Int, genres: List<String> = emptyList(),
               stoppedEarly: Boolean = false, persist: Boolean = true): List<Pair<String, Achievement>> {
        val out = ArrayList<Pair<String, Achievement>>()
        val since = entry.time - Achievements.NIGHT_MS
        val soloTonight = (before.filter { !it.duet && it.time >= since }.map { it.name.lowercase() } +
            (if (entry.duet) emptyList() else listOf(entry.name.lowercase()))).toSet().size
        val night = Achievements.nightOf(entry.time)
        recs()
        markParty(night)
        for (person in Achievements.people(entry)) {
            val key = person.lowercase()
            val r = recs().getOrPut(key) { Rec(person, 0, -1.0, Long.MIN_VALUE, 0, LinkedHashMap()) }
            r.name = person
            r.songs++
            if (r.lastNight != night) {
                // Sang at the party before this one too: the streak goes on (singers from before 3.27 start at 1)
                val before = partyBefore(night)
                r.streak = if (r.lastNight != Long.MIN_VALUE && r.lastNight == before) r.streak.coerceAtLeast(1) + 1 else 1
                r.bestStreak = maxOf(r.bestStreak, r.streak)
                r.lastNight = night; r.nights++
            }
            val mine = before.filter { Achievements.involves(it, person) }
            val ids = Achievements.earnedBy(entry, mine, r.best.takeIf { it >= 0 }, r.songs, r.nights, rank, soloTonight, hour,
                genres, person, stoppedEarly, r.emojis, r.hearts)
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
            record(e, done, rank = 0, hour = cal.get(java.util.Calendar.HOUR_OF_DAY), genres = Genres.detect(e.song, null), persist = false)
            done += e
        }
        save()
        prefs.edit().putBoolean("backfilled", true).apply()
    }

    /**
     * Once per new batch of achievements (genres in 3.16, the fun ones in 3.17, more in 3.23): awards them for
     * the scores already saved, dated to the party they were sung at. Counts that the saved
     * scores can't tell (songs ever sung, parties, personal bests, ranks) are left out here.
     */
    @Synchronized
    fun backfillNew(entries: List<Entry>) {
        if (prefs.getBoolean(BATCH, false)) return
        var changed = false
        val done = ArrayList<Entry>()
        val cal = java.util.Calendar.getInstance()
        entries.sortedBy { it.time }.forEach { e ->
            cal.timeInMillis = e.time
            val genres = Genres.detect(e.song, null)
            Achievements.people(e).forEach { person ->
                val r = recs()[person.lowercase()] ?: return@forEach
                val mine = done.filter { Achievements.involves(it, person) }
                Achievements.earnedBy(e, mine, null, 0, 0, 0, 0, cal.get(java.util.Calendar.HOUR_OF_DAY), genres, person)
                    .forEach { id -> if (id !in r.unlocked) { r.unlocked[id] = Achievements.nightOf(e.time); changed = true } }
            }
            done += e
        }
        if (changed) save()
        prefs.edit().putBoolean(BATCH, true).apply()
    }

    companion object {
        const val MAX = Leaderboard.MAX_PLAYERS
        /** Counts stop here: plenty for any achievement, and the saved numbers stay short. */
        const val CAP = 9999
        /** The emoji that counts for "Lover". */
        val HEART = "😍"
        /** Bumped with each new batch of achievements, so [backfillNew] runs again once. */
        const val BATCH = "more3"
        /** Skips for "No". */
        const val SKIPS = 10
        /** A singer's reputation follows roughly their last this many crowd-rated songs. */
        const val REP_WINDOW = 20
    }
}

/**
 * A singer's personal stats, for their page in the hall of fame. [favourite]: the song they've
 * sung most among the saved scores (ties: the better score, then the latest); [streak]: parties
 * in a row they sang at, still going; [bestStreak]: their longest run.
 */
data class PlayerStats(
    val songs: Int, val parties: Int, val best: Double?, val bestSong: String?,
    val favourite: String?, val favouriteTimes: Int, val streak: Int, val bestStreak: Int,
)

object Stats {
    fun of(rec: SingerRecord, entries: List<Entry>): PlayerStats {
        val mine = entries.filter { Achievements.involves(it, rec.name) }
        val bestSong = rec.best?.let { b ->
            mine.filter { !it.duet && it.score == b }.maxByOrNull { it.time }?.let { shortTitle(it.song) }
        }
        val fav = mine.groupBy { shortTitle(it.song).lowercase() }.values
            .maxWithOrNull(compareBy<List<Entry>> { it.size }.thenBy { l -> l.maxOf { it.score } }.thenBy { l -> l.maxOf { it.time } })
        return PlayerStats(
            songs = rec.songs, parties = rec.nights, best = rec.best, bestSong = bestSong,
            favourite = fav?.maxByOrNull { it.time }?.let { shortTitle(it.song) }, favouriteTimes = fav?.size ?: 0,
            streak = if (rec.streakAlive) rec.streak else 0, bestStreak = maxOf(rec.bestStreak, rec.streak),
        )
    }
}
