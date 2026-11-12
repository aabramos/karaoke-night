package com.karaoketv

import android.content.Context
import org.json.JSONArray
import java.util.Random
import kotlin.math.roundToInt

/**
 * The "judge". It doesn't listen: a song sung to the end gets a weighted random score between
 * [MIN] and 100 (most land between the mid 70s and low 90s; 95+ is a rare treat). Stopping early
 * gets 0 to [MIN], closer to [MIN] the further into the song the singer got.
 */
object Judge {
    private val rng = Random()
    const val MIN = 65
    /** From here on the song counts as finished (karaoke videos often end with a silent outro). */
    const val FINISHED = 0.9
    /** Stopping before this isn't scored at all (a wrong song stopped straight away). */
    const val TOO_SHORT = 0.1

    /** Points per step of the crowd's vote: a room that's all 🔥 (+2) adds 5, all 🍅 (-2) takes 5. */
    const val CROWD_POINTS = 2.5

    /** The crowd's hidden vote: the average of each phone's last cheer (-2 … +2); 0 without votes. */
    fun crowd(votes: Collection<Int>): Double = if (votes.isEmpty()) 0.0 else votes.average().coerceIn(-2.0, 2.0)

    /**
     * Null when the singer stopped too early to deserve a score. [crowd] (see [crowd]) shifts the
     * random score a little up or down; it never crosses the bands (65+ for a finished song).
     */
    fun score(watchedFraction: Double, crowd: Double = 0.0, rng: Random = this.rng): Int? {
        if (watchedFraction < TOO_SHORT) return null
        val nudge = CROWD_POINTS * crowd.coerceIn(-2.0, 2.0)
        if (watchedFraction >= FINISHED) return (83 + nudge + 8 * rng.nextGaussian()).roundToInt().coerceIn(MIN, 100)
        val progress = watchedFraction / FINISHED                      // 0.1 … 1
        return (MIN * progress + nudge * 0.6 + 6 * rng.nextGaussian()).roundToInt().coerceIn(0, MIN)
    }

    private val lines = listOf(
        100 to listOf("PERFECT!!!", "FLAWLESS!", "Is this the original?!"),
        95 to listOf("SUPERSTAR!", "STANDING OVATION!", "LEGENDARY!"),
        90 to listOf("AMAZING!", "What a voice!", "Goosebumps!"),
        85 to listOf("Great voice!", "Crowd pleaser!", "Nailed it!"),
        80 to listOf("Really good!", "Smooth!", "So much style!"),
        75 to listOf("Nice one!", "Solid!", "Rocking it!"),
        70 to listOf("Not bad!", "Brave effort!", "Fun times!"),
        65 to listOf("Warming up…", "Next one's yours!"),
        50 to listOf("So close to the end!", "Almost made it!"),
        30 to listOf("Half a song, full heart!", "A sneak preview!"),
        0 to listOf("Well… you tried! 😅", "The spirit was there!", "Short and sweet!"),
    )

    fun verdict(score: Int): String = lines.first { score >= it.first }.second.let { it[rng.nextInt(it.size)] }

    fun stars(score: Int): Int = when {
        score >= 95 -> 5
        score >= 88 -> 4
        score >= 80 -> 3
        score >= 70 -> 2
        score >= 40 -> 1
        else -> 0
    }
}

/** One of the (fake) celebrity judges and the paddle they hold up. */
data class JudgeCard(val face: String, val name: String, val points: Double, val quip: String) {
    /** "8.5", "9" or "10" for the paddle. */
    val paddle: String get() = if (points % 1.0 == 0.0) points.toInt().toString() else String.format(java.util.Locale.US, "%.1f", points)
}

/**
 * Rare extras on the score screen, only for great scores (85+), and not every time: usually there
 * are none, and when there are, it's a surprise which ones (the judges, the emoji crowd, or both).
 */
object Extras {
    enum class Kind { JUDGES, EMOJIS }
    const val MIN_SCORE = 85
    /** About one great score in eight gets something. */
    const val CHANCE = 0.12

    fun pick(score: Int, rng: Random = Random()): Set<Kind> {
        if (score < MIN_SCORE || rng.nextDouble() >= CHANCE) return emptySet()
        return when (rng.nextInt(3)) {
            0 -> setOf(Kind.JUDGES)
            1 -> setOf(Kind.EMOJIS)
            else -> setOf(Kind.JUDGES, Kind.EMOJIS)
        }
    }

    /**
     * The judging panel: four judges (two each side of the score, so it stays centred), each
     * paddle close to the real score out of 10, each judge with their own personality.
     */
    fun judges(score: Int, rng: Random = Random()): List<JudgeCard> {
        val base = score / 10.0
        fun half(x: Double) = (Math.round(x * 2) / 2.0).coerceIn(6.5, 10.0)
        fun pick(list: List<String>) = list[rng.nextInt(list.size)]
        val cat = half(base + rng.nextGaussian() * 0.8)
        val robot = (Math.round((base + rng.nextGaussian() * 0.3) * 10) / 10.0).coerceIn(6.5, 10.0)
        val gran = half(base + 0.5 + kotlin.math.abs(rng.nextGaussian()) * 0.4)
        val parrot = half(base + rng.nextGaussian() * 0.5)
        return listOf(
            JudgeCard("🐱", "WHISKERS", cat, pick(when {
                cat >= 9.5 -> listOf("Purr-fect!", "Meow-velous!", "I'd wake up for that")
                cat >= 8.5 -> listOf("Not bad, human", "Worth a slow blink", "Acceptable. Feed me.")
                else -> listOf("*yawns*", "Meh-ow", "I've heard better… from dogs")
            })),
            JudgeCard("🤖", "ROBOJUDGE", robot, pick(when {
                robot >= 9.5 -> listOf("ERROR: TOO GOOD", "SYSTEM OVERLOAD!", "UPLOADING TO MY PLAYLIST")
                robot >= 8.5 -> listOf("PITCH 97% HUMAN", "BEEP BOOP. APPROVED.", "EMOTION DETECTED")
                else -> listOf("ACCEPTABLE OUTPUT", "BEEP. OK. BEEP.", "RECALIBRATING EARS…")
            })),
            JudgeCard("👵", "GRANDMA ROSA", gran, pick(when {
                gran >= 9.5 -> listOf("I cried, darling!", "Better than Sinatra!", "Marry this voice!")
                gran >= 8.5 -> listOf("Lovely, dear!", "So proud of you!", "That's my favourite!")
                else -> listOf("Have a cookie, sweetie 🍪", "You did your best, dear", "Louder next time, love!")
            })),
            JudgeCard("🦜", "CAPTAIN POLLY", parrot, pick(when {
                parrot >= 9.5 -> listOf("SQUAWK! ENCORE! ENCORE!", "Polly wants a replay!", "Shiver me timbers!")
                parrot >= 8.5 -> listOf("SQUAWK! Pretty voice!", "Arrr, not bad!", "Polly likes it!")
                else -> listOf("SQUAWK?", "Polly wants a cracker", "Arrr… again!")
            })),
        )
    }

    /** The crowd: more and happier emojis for higher scores. */
    fun emojis(score: Int): Pair<List<String>, Float> = when {
        score >= 95 -> listOf("👏", "😍", "🔥", "🎉", "❤️", "🤩", "🙌", "⭐") to 8f
        score >= 90 -> listOf("👏", "😍", "🔥", "🎉", "❤️", "🙌") to 5.5f
        else -> listOf("👏", "🎶", "👍", "❤️", "😊", "🙌") to 4f
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
            val arr = runCatching { JSONArray(prefs.getString("entries", null) ?: "[]") }.getOrElse { JSONArray() }
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
        val arr = runCatching { JSONArray(prefs.getString("p", null) ?: prefs.getString("played", null) ?: "[]") }.getOrElse { JSONArray() }
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
