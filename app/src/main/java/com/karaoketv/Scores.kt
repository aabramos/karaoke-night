package com.karaoketv

import android.content.Context
import org.json.JSONArray
import java.util.Random
import kotlin.math.roundToInt

/**
 * The "judge". It doesn't listen: a song sung to the end gets a weighted random score between
 * [MIN] and 100, mostly spread evenly over 70 to 89 (see [ODDS]; 95+ is a rare treat and 100 the rarest).
 * The room decides most of it: the phones' emojis pull that roll towards what they thought (up to
 * [ROOM_WEIGHT] of the way, and only in the direction the room feels), the hype bar adds or takes [Hype.BONUS] and a singer's reputation up to
 * [REP_MAX]. Stopping early gets 0 to [MIN], closer to [MIN] the further into the song the singer got.
 */
object Judge {
    private val rng = Random()
    const val MIN = 65
    /** From here on the song counts as finished (karaoke videos often end with a silent outro). */
    const val FINISHED = 0.9
    /** Stopping before this isn't scored at all (a wrong song stopped straight away). */
    const val TOO_SHORT = 0.1

    /**
     * How a finished song's score is picked when the phones stay quiet: the chance of each score
     * from [MIN] to 99, out of 1000. 70 to 89 share three quarters of the songs almost evenly
     * (4.3% for a 70, sloping gently down to 3.2% for an 89), so no score comes up over and over.
     * Below 70 and above 89 get rarer step by step, and 100 is the rarest of all. About 79.5 on average.
     * By band: 65-69 12%, 70-74 20.9%, 75-79 19.5%, 80-84 18%, 85-89 16.6%, 90-94 9%, 95-99 3.6%, 100 0.4%.
     */
    private val ODDS = intArrayOf(
        16, 20, 24, 28, 32,                          // 65-69
        43, 42, 42, 41, 41, 40, 40, 39, 38, 38,      // 70-79
        37, 37, 36, 35, 35,                          // 80-84
        34, 34, 33, 33, 32,                          // 85-89
        26, 21, 17, 14, 12,                          // 90-94
        10, 8, 7, 6, 5,                              // 95-99
    )
    /** A perfect 100, out of 1000. */
    private const val ODDS_100 = 4

    /** The judge's pick for a finished song, before the crowd (see [ODDS]). */
    fun baseScore(rng: Random = this.rng): Int {
        var r = rng.nextInt(ODDS.sum() + ODDS_100)
        for (i in ODDS.indices) {
            if (r < ODDS[i]) return MIN + i
            r -= ODDS[i]
        }
        return 100
    }
    /** How much the room can decide of a score: with the whole room voting, 60% (luck keeps 40%). */
    const val ROOM_WEIGHT = 0.6
    /** The room's say grows in steps of 1/5 of [ROOM_WEIGHT]: 2/5 with one phone, then 3/5, 4/5 and 5/5 with four or more. */
    const val CROWD_MAX = 5
    /** The least say the votes get (one phone, or a few phones in a big room), out of [CROWD_MAX]. */
    const val CROWD_MIN = 2
    /** The score a room points to: 80 for mixed votes, 6 more or less per point of the average vote (68 … 92). */
    const val TARGET_MID = 80.0
    const val TARGET_STEP = 6.0

    /**
     * The phones' verdict on a song: their [average] vote (-2 … +2) and how much it counts ([weight],
     * 0 … [ROOM_WEIGHT]). The score is pulled from the random roll towards [target] by [weight], but
     * only the way the room feels (see [score]).
     */
    class Crowd(val average: Double, val weight: Double) {
        /** 92 when everyone loved it, 80 when votes were mixed, 68 when everyone booed. */
        val target: Double get() = TARGET_MID + TARGET_STEP * average
        /** About how many points it moves an average roll (used for early stops). */
        val points: Double get() = TARGET_STEP * average * weight
        companion object { val NONE = Crowd(0.0, 0.0) }
    }

    /**
     * The crowd's hidden vote: each phone's own vote (the average of its emojis, -2 … +2) is
     * averaged, so a phone sending lots of emojis counts once, like the rest. How much it counts is
     * worked out twice, and the smaller one wins:
     *  - by how many phones voted, so one phone can't swing a song like a whole room (2/5 to 5/5)
     *  - by what share of the room voted ([room]: phones with the party page open), so ten phones
     *    at a party of thirty count for less than ten at a party of ten. 0: room size unknown.
     */
    fun crowd(votes: Collection<Number>, room: Int = 0): Crowd {
        if (votes.isEmpty()) return Crowd.NONE
        val n = votes.size
        val byCount = minOf(CROWD_MAX, n + 1).toDouble()
        val byShare = CROWD_MIN + (CROWD_MAX - CROWD_MIN) * n.toDouble() / maxOf(n, room)
        return Crowd(votes.map { it.toDouble() }.average().coerceIn(-2.0, 2.0), ROOM_WEIGHT * minOf(byCount, byShare) / CROWD_MAX)
    }

    /** The most a singer's reputation can move the score (kept small: tonight's room matters most). */
    const val REP_MAX = 1.0
    /** Songs rated by past crowds before a reputation counts half; it's trusted more with each one. */
    const val REP_TRUST = 5
    /** A song's votes shape the singer's reputation only when at least this many phones voted (not just their own). */
    const val REP_VOTERS = 2

    /**
     * Points from a singer's past crowds: [average] is their crowds' average vote (-2 … +2) over
     * [rated] songs. A newcomer gets 0, and it grows slowly ([REP_TRUST]), never past ±[REP_MAX].
     */
    fun reputation(average: Double, rated: Int): Double =
        if (rated <= 0) 0.0 else REP_MAX * (average / 2).coerceIn(-1.0, 1.0) * rated / (rated + REP_TRUST)

    /**
     * Null when the singer stopped too early to deserve a score. A finished song: the random roll
     * is pulled towards what the room thought ([crowd], see [Crowd]), only ever the way the room
     * feels (a loving room never lowers a lucky roll, a booing one never lifts an unlucky one), then [bonus] (the hype bar,
     * + or -) and [reputation] (past crowds, see [reputation]) are added. With no votes, the roll
     * stands. Nothing crosses the bands (65+ for a finished song, below that for an early stop).
     */
    fun score(watchedFraction: Double, crowd: Crowd = Crowd.NONE, rng: Random = this.rng, bonus: Int = 0, reputation: Double = 0.0): Double? {
        if (watchedFraction < TOO_SHORT) return null
        val extra = bonus + reputation.coerceIn(-REP_MAX, REP_MAX)
        if (watchedFraction >= FINISHED) {
            val base = baseScore(rng)
            // Only a 100 the room didn't turn against stays 100 (it's the rarest of all)
            if (base == 100 && crowd.average >= 0 && extra >= 0) return 100.0
            val w = crowd.weight.coerceIn(0.0, ROOM_WEIGHT)
            // The roll is a whole number: spreading it over its tenths (82 becomes 81.5 … 82.4) gives
            // every decimal the same chance, so equal scores stay rare
            val tenths = rng.nextDouble() - 0.5
            // Only the way the room feels; a room split exactly down the middle has no verdict to push
            val pull = (w * (crowd.target - base)).let { p ->
                when {
                    crowd.average > 0 -> maxOf(0.0, p)
                    crowd.average < 0 -> minOf(0.0, p)
                    else -> 0.0
                }
            }
            // Past either end, land somewhere in the last point (65.0 … 65.9, 99.0 … 99.9) rather than
            // all on exactly 65.0 or 99.9, which would make ties there
            val raw = base + pull + extra + tenths
            return tenth(when {
                raw < MIN -> MIN + rng.nextDouble() * 0.9
                raw > TOP -> TOP - rng.nextDouble() * 0.9
                else -> raw
            }).coerceIn(MIN.toDouble(), TOP)
        }
        val progress = watchedFraction / FINISHED                      // 0.1 … 1
        return tenth(MIN * progress + (crowd.points + extra) * 0.6 + 6 * rng.nextGaussian()).coerceIn(0.0, MIN.toDouble())
    }

    /** The best score short of a perfect 100. */
    const val TOP = 99.9

    /** Rounded to one decimal, the way scores are shown. */
    fun tenth(x: Double): Double = Math.round(x * 10) / 10.0

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

    fun verdict(score: Double): String = lines.first { score >= it.first }.second.let { it[rng.nextInt(it.size)] }

    fun stars(score: Double): Int = when {
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
 * The crowd's hype during one song, from -1 to +1: 🔥 😍 👏 🎉 😂 push it up, 😴 🍅 push it down.
 * It holds while the room keeps cheering and only drifts back to 0, slowly, once nobody has sent
 * anything for [HOLD_MS]. At a small party (up to [SMALL_ROOM] phones) every cheer counts fully,
 * so one phone can fill it in about a minute of steady cheering; in a bigger room each cheer counts
 * a little less, so one superfan can't do it alone, but the whole room still gets there quickly.
 * Reaching +1 ([full]) gives [BONUS] points
 * and a 🔥; reaching -1 ([booed]) takes [BONUS] away and shows a 🍅. Either way it then stays there.
 */
class Hype {
    var level = 0.0
        private set
    var full = false
        private set
    var booed = false
        private set
    private var last = 0L
    private var lastCheer = 0L

    /** Points for the score: +[BONUS], -[BONUS] or 0. */
    val points: Int get() = if (full) BONUS else if (booed) -BONUS else 0

    private val done get() = full || booed

    fun reset() { level = 0.0; full = false; booed = false; last = 0L; lastCheer = 0L }

    /** The level at [now], after drifting back towards 0 for the time since the last cheer, minus [HOLD_MS]. */
    fun at(now: Long): Double {
        if (!done && last > 0) {
            val quietFrom = maxOf(last, lastCheer + HOLD_MS)       // the bar holds for HOLD_MS after each cheer
            val drift = DRAIN_PER_S * (now - quietFrom).coerceAtLeast(0L) / 1000.0
            level = if (level > 0) (level - drift).coerceAtLeast(0.0) else (level + drift).coerceAtMost(0.0)
        }
        last = now
        return if (full) 1.0 else if (booed) -1.0 else level
    }

    /** While the song is paused the bar doesn't drift: the time since the last update doesn't count. */
    fun freeze(now: Long) {
        if (last > 0) { lastCheer += now - last; last = now }
    }

    /** A cheer at [now], with [room] phones on the party page (0: unknown): true if it took the bar to either end. */
    fun cheer(now: Long, positive: Boolean, room: Int = 0): Boolean {
        if (done) return false
        at(now)
        lastCheer = now
        val step = PER_CHEER * weight(room)
        level = (level + if (positive) step else -step).coerceIn(-1.0, 1.0)
        if (level >= 1.0) { full = true; return true }
        if (level <= -1.0) { booed = true; return true }
        return false
    }

    companion object {
        /** How far one cheer moves the bar at a small party: 25 fill it (one phone cheering every 3 s: 75 s). */
        const val PER_CHEER = 0.04
        /** Up to this many phones, every cheer counts fully. */
        const val SMALL_ROOM = 3

        /** How much one cheer counts with [room] phones: 1 up to [SMALL_ROOM], then √(3/room) (20 phones: 0.39). */
        fun weight(room: Int): Double = if (room <= SMALL_ROOM) 1.0 else Math.sqrt(SMALL_ROOM.toDouble() / room)
        /** After each cheer the bar holds this long before it starts drifting back. */
        const val HOLD_MS = 10_000L
        /** Then it drifts back by this much per second (a half-full bar takes 50 s to empty). */
        const val DRAIN_PER_S = 0.01
        /** Points for reaching either end (the score stays within its band: 65 to 100 for a finished song). */
        const val BONUS = 3
    }
}

/**
 * Rare extras on the score screen, only for great scores (85+), and not every time: usually there
 * are none, and when there are, it's a surprise which ones (the judges, the emoji crowd, or both).
 */
object Extras {
    enum class Kind { JUDGES, EMOJIS }
    const val MIN_SCORE = 85
    /** One great score (85+) in four gets something: the judges, the emoji crowd, or both. */
    const val CHANCE = 0.25
    /** Above [TOP_SCORE] the judges come out half the time. */
    const val TOP_SCORE = 95
    const val TOP_JUDGES = 0.5
    /** More emojis than this from the phones during the song: the crowd always shows (with those emojis). */
    const val CROWD_EMOJIS = 50

    /** [emojisSent]: emojis the phones sent during this song. */
    fun pick(score: Double, emojisSent: Int = 0, rng: Random = Random()): Set<Kind> {
        val out = LinkedHashSet<Kind>()
        if (score >= MIN_SCORE && rng.nextDouble() < CHANCE) out += when (rng.nextInt(3)) {
            0 -> setOf(Kind.JUDGES)
            1 -> setOf(Kind.EMOJIS)
            else -> setOf(Kind.JUDGES, Kind.EMOJIS)
        }
        // Judges so far: CHANCE × 2/3. Top this up so that, above TOP_SCORE, they come out TOP_JUDGES of the time.
        if (score > TOP_SCORE && Kind.JUDGES !in out) {
            val already = CHANCE * 2 / 3
            if (rng.nextDouble() < (TOP_JUDGES - already) / (1 - already)) out += Kind.JUDGES
        }
        if (emojisSent > CROWD_EMOJIS) out += Kind.EMOJIS
        return out
    }

    /**
     * The judging panel: four judges (two each side of the score, so it stays centred), each
     * paddle close to the real score out of 10, each judge with their own personality.
     */
    fun judges(score: Double, rng: Random = Random()): List<JudgeCard> {
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
            JudgeCard("👵", "GRANDMA", gran, pick(when {
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
    /**
     * The emoji crowd: the emojis the phones actually sent (most sent first) when there were lots,
     * otherwise a set that fits the score. The second value is how many float up per second.
     */
    fun emojis(score: Double, sent: Map<String, Int> = emptyMap()): Pair<List<String>, Float> {
        val total = sent.values.sum()
        if (total > CROWD_EMOJIS) {
            val top = sent.entries.sortedByDescending { it.value }.map { it.key }.take(6)
            return top to (4f + total / 25f).coerceAtMost(9f)
        }
        return byScore(score)
    }

    private fun byScore(score: Double): Pair<List<String>, Float> = when {
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
    fun earned(entry: Entry, tonightBefore: List<Entry>, songsBefore: Int, bestBefore: Double?, hour: Int, limit: Int = 2): List<String> {
        val tonight = tonightBefore.filter { it.duet == entry.duet && it.name.equals(entry.name, ignoreCase = true) }.sortedBy { it.time }
        val out = ArrayList<String>()
        if (entry.score >= 100) out += "💯 PERFECT SCORE"
        if (!entry.duet) {
            if (songsBefore == 0) out += "🎤 Debut performance!"
            else if (bestBefore != null) when {
                entry.score > bestBefore -> out += "📈 New personal best! (was ${scoreText(bestBefore)})"
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
