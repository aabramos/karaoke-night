package com.karaoketv

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/** [views]: YouTube view count, 0 if unknown. */
data class Song(val id: String, val title: String, val channel: String, val duration: String, val views: Long = 0) {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** 1234567 → "1.2M views". Empty when unknown. */
fun viewsLabel(n: Long): String = when {
    n <= 0 -> ""
    n >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1fB views", n / 1e9).replace(".0B", "B")
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM views", n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "${n / 1000}K views"
    else -> "$n views"
}

/** [retries]: how many times another version of the song was tried because a video refused to play. */
/** [singer] is one name, or two joined by [DUET_JOIN] when [duet] ("Ana & Rui"). */
data class QueueItem(
    val song: Song,
    val singer: String,
    val fromPhone: Boolean,
    val key: Long = nextKey(),
    val retries: Int = 0,
    val duet: Boolean = false,
) {
    companion object {
        private var counter = 0L
        @Synchronized private fun nextKey() = ++counter
    }
}

private const val BIDI_MARKS = "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
fun isVideoId(s: String?) = s != null && VIDEO_ID.matches(s)

/** Trims a singer name, removes control characters and caps the length. Null if empty. */
fun cleanName(raw: String?): String? {
    if (raw == null) return null
    // Control characters and invisible text-direction marks (which could make a name display reversed)
    val s = raw.filter { !it.isISOControl() && it !in BIDI_MARKS }.trim().replace(Regex("\\s+"), " ")
    return s.take(20).ifEmpty { null }
}

/** A duet's display name from two raw names, or null if either is empty or they're the same person. */
fun duetName(a: String?, b: String?): String? {
    val x = cleanName(a) ?: return null
    val y = cleanName(b) ?: return null
    if (x.equals(y, ignoreCase = true)) return null
    return x + DUET_JOIN + y
}

/**
 * The song queue. Phones (server threads) and the remote (main thread) both add to it,
 * so every access is synchronised and change notifications are delivered on the main thread.
 */
class PartyQueue(private val main: Handler) {
    private val items = ArrayList<QueueItem>()
    var onChanged: (() -> Unit)? = null

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
            if (items.count { it.singer.equals(item.singer, ignoreCase = true) } >= MAX_PER_SINGER)
                return "${item.singer} already has $MAX_PER_SINGER songs waiting. Let others sing too!"
            items.add(item)
        }
        changed()
        return null
    }

    /** 1-based position of an item, or 0. */
    fun positionOf(key: Long): Int = synchronized(items) { items.indexOfFirst { it.key == key } + 1 }

    fun pollNext(): QueueItem? {
        val next = synchronized(items) { if (items.isEmpty()) null else items.removeAt(0) }
        if (next != null) changed()
        return next
    }

    fun peekNext(): QueueItem? = synchronized(items) { items.firstOrNull() }

    fun pushFront(item: QueueItem) {
        synchronized(items) { items.add(0, item) }
        changed()
    }

    fun pushBack(item: QueueItem) {
        synchronized(items) { items.add(item) }
        changed()
    }

    fun remove(key: Long) {
        synchronized(items) { items.removeAll { it.key == key } }
        changed()
    }

    fun moveToTop(key: Long) {
        synchronized(items) {
            val i = items.indexOfFirst { it.key == key }
            if (i > 0) items.add(0, items.removeAt(i))
        }
        changed()
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
 * Answers the guests' phones (called on the server's worker threads). It deliberately holds no
 * strong reference to the screen: only a weak one, used for the "song added" pop-up.
 */
class PartyBackend(
    private val queue: PartyQueue,
    private val search: SongSearch,
    private val board: Leaderboard,
    listener: Listener,
) : PartyServer.Backend {

    interface Listener {
        fun onPhoneAdded(item: QueueItem)
        /** A phone removed a player: refresh anything on screen that lists players or scores. */
        fun onPlayersChanged()
    }

    private val listener = WeakReference(listener)
    private val main = Handler(Looper.getMainLooper())

    override fun searchJson(query: String): String {
        val arr = JSONArray()
        search.search(query).forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("dur", it.duration)
                .put("views", viewsLabel(it.views)))
        }
        return arr.toString()
    }

    override fun stateJson(): String {
        val o = JSONObject()
        queue.current?.let { o.put("now", JSONObject().put("singer", it.singer).put("title", it.song.title)) }
        val arr = JSONArray()
        queue.snapshot().forEach { arr.put(JSONObject().put("singer", it.singer).put("title", it.song.title)) }
        // Phones show the same quick picks as the TV
        val c = search.country
        val picks = JSONArray()
        Countries.picks(c).forEach { (label, q) -> picks.put(JSONArray().put(label).put(q)) }
        o.put("country", JSONObject().put("code", c.code).put("name", c.name).put("flag", c.flag))
        o.put("picks", picks)
        // Everyone who has sung or been picked, so guests can tap their name instead of typing it
        o.put("players", JSONArray().also { a -> board.players().forEach { a.put(it) } })
        return o.put("queue", arr).toString()
    }

    override fun add(videoId: String?, singer: String?, partner: String?): String? {
        if (videoId == null || !isVideoId(videoId)) return "That song can't be added."
        val song = search.lookup(videoId) ?: return "Search for the song first, then add it."
        val solo = cleanName(singer) ?: return "Type your name first."
        val duet = !partner.isNullOrBlank()
        val name = if (duet) duetName(solo, partner) ?: return "Type a different name for your duet partner." else solo
        val item = QueueItem(song, name, fromPhone = true, duet = duet)
        val err = queue.add(item)
        if (err == null) {
            board.rememberPlayers(name.split(DUET_JOIN))
            main.post { listener.get()?.onPhoneAdded(item) }
        }
        return err
    }

    override fun removePlayer(name: String?): String? {
        if (name.isNullOrBlank()) return "Choose a player to remove."
        if (!board.removePlayer(name)) return "That player isn't on the list any more."
        main.post { listener.get()?.onPlayersChanged() }
        return null
    }
}

/** Saves the queue (including whoever is on stage) so an accidental exit or crash doesn't lose it. */
object QueueStore {
    fun save(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = JSONArray()
        (listOfNotNull(queue.current) + queue.snapshot()).forEach {
            arr.put(JSONObject().put("i", it.song.id).put("t", it.song.title).put("c", it.song.channel)
                .put("d", it.song.duration).put("s", it.singer).put("p", it.fromPhone).put("du", it.duet))
        }
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    fun restore(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i")
            val duet = o.optBoolean("du", false)
            val raw = o.optString("s")
            val name = (if (duet) raw.split(DUET_JOIN).let { duetName(it.getOrNull(0), it.getOrNull(1)) } else cleanName(raw)) ?: continue
            if (!isVideoId(id)) continue
            queue.add(QueueItem(Song(id, o.optString("t").take(150), o.optString("c").take(80), o.optString("d").take(10)),
                name, o.optBoolean("p", false), duet = duet))
        }
    }
}
