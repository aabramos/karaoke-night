package com.karaoketv

import android.os.Handler
import org.json.JSONArray

// The song queue: who sings what next, the fair-queue order, and saving it across restarts.

/**
 * A song in the queue. [singer] is one name, or two joined by [DUET_JOIN] when [duet] ("Ana & Rui").
 * [retries]: how many times another version was tried because a video refused to play.
 * [owner]: for songs added from a phone, a fingerprint of that phone's ID (see [ownerTag]), so
 * that phone can take its own song back out of the queue; null for songs added on the TV.
 * [genres]: the song's genres (see [Genres.detect]), worked out when it was added.
 */
data class QueueItem(
    val song: Song,
    val singer: String,
    val fromPhone: Boolean,
    val key: Long = nextKey(),
    val retries: Int = 0,
    val duet: Boolean = false,
    val owner: String? = null,
    val genres: List<String> = emptyList(),
    /** "For Bia": shown for a few seconds before the song starts (see [dedicationFor]). */
    val dedication: String? = null,
    /** Dealt by a 🎭 themed party: doesn't count toward the singer's own limit of waiting songs. */
    val themed: Boolean = false,
) {
    companion object {
        // Starts from the clock, so keys stay unique after the app restarts (phones remember theirs)
        private var counter = System.currentTimeMillis()
        @Synchronized private fun nextKey() = ++counter
        /** A restored song keeps its key; new keys must not reuse it. */
        @Synchronized fun noteKey(key: Long) { if (key > counter) counter = key }
    }
}

/**
 * The song queue. Phones (server threads) and the remote (main thread) both add to it,
 * so every access is synchronised and change notifications are delivered on the main thread.
 */
class PartyQueue(private val main: Handler) {
    private val items = ArrayList<QueueItem>()
    var onChanged: (() -> Unit)? = null

    /**
     * Fair queue: songs are put in rounds, so nobody sings twice before everyone has sung once.
     * [sungTonight] tells how many songs each person (lower-case name) has already sung tonight.
     * A song moved with "Sing next" stays first; one sent to the end ("later") stays last.
     */
    @Volatile var fair = false
        set(value) { field = value; if (value) { synchronized(items) { rebalance() }; changed() } }
    @Volatile var sungTonight: () -> Map<String, Int> = { emptyMap() }
    private val pinned = HashSet<Long>()
    private val deferred = HashSet<Long>()

    /** Who is singing right now (read by the phone server too). */
    @Volatile var current: QueueItem? = null

    fun snapshot(): List<QueueItem> = synchronized(items) { items.toList() }
    val size: Int get() = synchronized(items) { items.size }
    fun isEmpty() = size == 0

    /** Returns null on success, or a short reason. */
    fun add(item: QueueItem): String? {
        synchronized(items) {
            if (items.size >= MAX) return "The queue is full! Wait for a few songs to finish."
            if (items.any { it.song.id == item.song.id } || current?.song?.id == item.song.id)
                return "That song is already in the queue."
            if (items.count { !it.themed && it.singer.equals(item.singer, ignoreCase = true) } >= MAX_PER_SINGER)
                return "${item.singer} already has $MAX_PER_SINGER songs waiting. Let others sing too!"
            items.add(item)
            if (fair) rebalance()
        }
        changed()
        return null
    }

    /**
     * A themed party's songs, added after what is already waiting (and placed by round when the
     * fair queue is on). Skips songs already queued; no per-singer limit, since the TV chose them.
     * Returns the ones that went in (the queue holds [MAX]).
     */
    fun addAll(list: List<QueueItem>): List<QueueItem> {
        val added = ArrayList<QueueItem>()
        synchronized(items) {
            for (item in list) {
                if (items.size >= MAX) break
                if (items.any { it.song.id == item.song.id } || current?.song?.id == item.song.id) continue
                items.add(item); added += item
            }
            if (added.isNotEmpty() && fair) rebalance()
        }
        if (added.isNotEmpty()) changed()
        return added
    }

    /** Empties the queue (the song being sung stays). Returns how many songs were taken out. */
    fun clear(): Int {
        val n = synchronized(items) { items.size.also { items.clear(); pinned.clear(); deferred.clear() } }
        if (n > 0) changed()
        return n
    }

    /** 1-based position of an item, or 0. */
    fun positionOf(key: Long): Int = synchronized(items) { items.indexOfFirst { it.key == key } + 1 }

    fun pollNext(): QueueItem? {
        val next = synchronized(items) {
            if (items.isEmpty()) null else items.removeAt(0).also { pinned.remove(it.key); deferred.remove(it.key) }
        }
        if (next != null) changed()
        return next
    }

    fun peekNext(): QueueItem? = synchronized(items) { items.firstOrNull() }

    /** Back to the front (the next singer, sent back to the lobby): they keep their turn, fair queue or not. */
    fun pushFront(item: QueueItem) {
        synchronized(items) { items.add(0, item); pinned.clear(); pinned += item.key; deferred -= item.key }
        changed()
    }

    fun pushBack(item: QueueItem) {
        synchronized(items) { items.add(item); deferred += item.key; pinned -= item.key }
        changed()
    }

    fun remove(key: Long) {
        synchronized(items) { items.removeAll { it.key == key }; pinned -= key; deferred -= key }
        changed()
    }

    /** Takes a waiting song out of the queue if [owner] added it. Returns the song, or null. */
    fun removeOwned(key: Long, owner: String): QueueItem? {
        val removed = synchronized(items) {
            val i = items.indexOfFirst { it.key == key && it.owner == owner }
            if (i < 0) null else items.removeAt(i)
        }
        if (removed != null) changed()
        return removed
    }

    fun contains(key: Long) = synchronized(items) { items.any { it.key == key } }

    fun moveToTop(key: Long) {
        synchronized(items) {
            val i = items.indexOfFirst { it.key == key }
            if (i > 0) items.add(0, items.removeAt(i))
            if (i >= 0) { pinned.clear(); pinned += key; deferred -= key }
        }
        changed()
    }

    /**
     * Fair order: each song's round is how many songs its singer has had before it (sung tonight,
     * plus their earlier songs in the queue); a duet goes by whichever partner has had more.
     * The sort is stable, so songs in the same round keep their order. Call holding the lock.
     */
    private fun rebalance() {
        if (items.size < 2) return
        val sung = runCatching { sungTonight() }.getOrDefault(emptyMap())
        val seen = HashMap<String, Int>()
        val round = HashMap<Long, Int>()
        for (it in items) {
            val people = it.singer.split(DUET_JOIN).map { p -> p.lowercase() }
            round[it.key] = when (it.key) {
                in pinned -> -1
                in deferred -> Int.MAX_VALUE
                else -> people.maxOf { p -> (sung[p] ?: 0) + (seen[p] ?: 0) }
            }
            people.forEach { p -> seen[p] = (seen[p] ?: 0) + 1 }
        }
        val sorted = items.sortedBy { round[it.key] }
        items.clear(); items.addAll(sorted)
    }

    private fun changed() {
        main.post { onChanged?.invoke() }
    }

    companion object {
        const val MAX = 50
        const val MAX_PER_SINGER = 3
    }
}

/**
 * Saves the queue (including whoever is on stage) so an accidental exit or crash doesn't lose it.
 * One short row per song: [video ID, title, channel, duration, singer, flags, key, phone fingerprint,
 * genres] (flags: 1 = added from a phone, 2 = duet; the fingerprint is empty for songs added on the
 * TV; genres like "rock,pop" only when there are any).
 */
object QueueStore {
    fun save(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = JSONArray()
        (listOfNotNull(queue.current) + queue.snapshot()).forEach {
            arr.put(JSONArray().put(it.song.id).put(it.song.title.take(100)).put(it.song.channel.take(40))
                .put(it.song.duration).put(it.singer).put((if (it.fromPhone) 1 else 0) or (if (it.duet) 2 else 0) or (if (it.themed) 4 else 0))
                .put(it.key).put(it.owner ?: "").put(it.genres.joinToString(","))
                .apply { it.dedication?.let { d -> put(d) } })
        }
        prefs.edit().putString("q", arr.toString()).remove("queue").apply()
    }

    fun restore(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = runCatching { JSONArray(prefs.getString("q", null) ?: "[]") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val id = r.optString(0)
            val flags = r.optInt(5)
            val duet = flags and 2 != 0
            val raw = r.optString(4)
            val name = (if (duet) raw.split(DUET_JOIN).let { duetName(it.getOrNull(0), it.getOrNull(1)) } else cleanName(raw)) ?: continue
            if (!isVideoId(id)) continue
            val song = Song(id, r.optString(1).take(150), r.optString(2).take(80), r.optString(3).take(10))
            val key = r.optLong(6)
            val owner = r.optString(7).takeIf { it.length == 16 }
            val genres = r.optString(8).split(',').filter { Genres.get(it) != null }
            val item = QueueItem(song, name, flags and 1 != 0, duet = duet, owner = owner, genres = genres, dedication = cleanName(r.optString(9)),
                themed = flags and 4 != 0)
            queue.add(if (key > 0) { QueueItem.noteKey(key); item.copy(key = key) } else item)
        }
    }
}
