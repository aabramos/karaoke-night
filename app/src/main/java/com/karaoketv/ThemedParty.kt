package com.karaoketv

/**
 * 🎭 Themed party: turns a theme into a list of different songs and deals them to the chosen
 * singers in rounds (the fair-queue rule, whether or not the fair queue is on). Pure logic, so
 * it can be tested off the TV; the searching itself runs on the screen's worker thread.
 */
object ThemedParty {
    const val MIN_SONGS = 1
    const val MAX_SONGS = 30
    /** Songs a themed party remembers having dealt (to bring new ones each round). */
    const val MAX_USED = 300
    /** How long a themed party keeps trying more searches (once it has some songs). */
    const val SEARCH_BUDGET_MS = 25_000L

    private val DUET = Regex("""\b(duet|duets|dueto|duetos|duo|duos)\b""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""[\p{L}\p{N}']+""")
    private val STOP = setOf(
        "the", "a", "an", "of", "in", "by", "and", "with", "style", "official", "video", "audio", "lyric", "lyrics",
        "karaoke", "version", "instrumental", "sing", "along", "feat", "ft", "cover", "original", "hd", "hq", "4k",
        "key", "lower", "higher", "male", "female", "backing", "track", "made", "famous",
    )

    /** How many songs to offer at first: two each, at least four, and never more than fit. */
    fun defaultCount(singers: Int, room: Int): Int = maxOf(4, 2 * singers).coerceAtMost(maxCount(room)).coerceAtLeast(MIN_SONGS)

    /** The most a themed party may add when [room] places are left in the queue. */
    fun maxCount(room: Int): Int = room.coerceIn(0, MAX_SONGS)

    /** The searches to try, in order, until there are enough songs (each one adds "karaoke"). */
    fun queries(theme: String): List<String> {
        val q = theme.trim().replace(Regex("\\s+"), " ").take(60)
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        return buildList {
            add(q)
            if ("best" !in lower) add("best $q")
            if ("hits" !in lower) add("$q hits")
            if ("songs" !in lower) add("$q songs")
        }
    }

    /**
     * The words of a song's name, in any order, without the karaoke extras: "Queen - Bohemian
     * Rhapsody" and "Bohemian Rhapsody (in the style of Queen)" share their words, so one is
     * seen as a repeat of the other.
     */
    fun titleKey(title: String): Set<String> =
        WORD.findAll(shortTitle(title).lowercase()).map { it.value.trim('\'') }.filter { it.isNotEmpty() && it !in STOP }.toSet()

    /** Same song? Equal words, or one name holds all the words of the other (two words or more). */
    fun sameSong(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (small, big) = if (a.size <= b.size) a to b else b to a
        return small.size >= 2 && big.containsAll(small)
    }

    /**
     * Picks up to [count] different songs from [found] (most-watched first): none in [avoid]
     * (already queued, playing or sung recently), no duets, no repeats under another channel's
     * title. Draws from the best of them, so the list feels fresh each time but stays well known.
     */
    fun pick(found: List<Song>, count: Int, avoid: Collection<Song>, rng: java.util.Random): List<Song> {
        if (count <= 0) return emptyList()
        val pool = ArrayList(fresh(found, avoid).take(maxOf(count + 3, count * 3 / 2)))
        java.util.Collections.shuffle(pool, rng)
        return pool.take(count)
    }

    /** The usable songs in [found], most-watched first: see [pick]. */
    fun fresh(found: List<Song>, avoid: Collection<Song>): List<Song> {
        val avoidIds = avoid.mapTo(HashSet()) { it.id }
        val keys = avoid.map { titleKey(it.title) }.toMutableList()
        val out = ArrayList<Song>()
        for (song in found.sortedByDescending { it.views }) {
            if (song.id in avoidIds || DUET.containsMatchIn(song.title)) continue
            val key = titleKey(song.title)
            if (keys.any { sameSong(it, key) }) continue
            avoidIds += song.id; keys += key; out += song
        }
        return out
    }

    /**
     * Deals [songs] to [singers] in rounds: everyone gets one before anyone gets two. In each
     * round those who have sung (or are waiting) the least go first; [load] counts that, by
     * lower-case name. Ties are shuffled, so the same person isn't always first.
     */
    fun deal(songs: List<Song>, singers: List<String>, load: Map<String, Int>, rng: java.util.Random): List<Pair<Song, String>> {
        val people = singers.distinctBy { it.lowercase() }
        if (people.isEmpty()) return emptyList()
        val order = people.shuffled(rng).sortedBy { load[it.lowercase()] ?: 0 }
        return songs.mapIndexed { i, song -> song to order[i % order.size] }
    }

    /** Theme ideas for a country: its quick picks without the duets (a themed party is solo). */
    fun ideas(picks: List<Pair<String, String>>): List<Pair<String, String>> =
        picks.filter { (label, query) -> !label.startsWith("👫") && !DUET.containsMatchIn(query) }

    /** A quick pick's name without its emoji: "🎻 Fado" → "Fado". */
    fun ideaName(label: String): String = label.substringAfter(' ', label).trim()
}
