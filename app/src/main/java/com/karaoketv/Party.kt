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
    private val history: History,
    listener: Listener,
) : PartyServer.Backend {

    interface Listener {
        fun onPhoneAdded(item: QueueItem)
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

    // Every phone asks for this every few seconds: build it at most once a second and share it
    @Volatile private var cachedState: String? = null
    @Volatile private var cachedAt = 0L

    /** Call after anything phones show has changed, so the next update is fresh. */
    fun invalidate() { cachedState = null }

    override fun stateJson(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedState?.let { if (now - cachedAt < STATE_CACHE_MS) return it }
        return buildState().also { cachedState = it; cachedAt = now }
    }

    private fun buildState(): String {
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
        // Tonight's top 3 singers (best score each) and the best duet, for the phones' podium
        o.put("top", JSONArray().also { a ->
            board.tonightBest(3).forEach { a.put(JSONObject().put("name", it.name).put("score", it.score)) }
        })
        board.tonightBest(1, duet = true).firstOrNull()?.let { o.put("duet", JSONObject().put("name", it.name).put("score", it.score)) }
        // The last songs sung, to sing again ("🕘 Played")
        val played = history.all()
        search.remember(played.map { it.song })
        o.put("recent", JSONArray().also { a ->
            played.forEach {
                a.put(JSONObject().put("id", it.song.id).put("title", it.song.title).put("channel", History.label(it))
                    .put("dur", it.song.duration))
            }
        })
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
            invalidate()
            board.rememberPlayers(name.split(DUET_JOIN))
            main.post { listener.get()?.onPhoneAdded(item) }
        }
        return err
    }
}

private const val STATE_CACHE_MS = 1000L

/**
 * Saves the queue (including whoever is on stage) so an accidental exit or crash doesn't lose it.
 * One short row per song: [video ID, title, channel, duration, singer, flags] (flags: 1 = added
 * from a phone, 2 = duet).
 */
object QueueStore {
    fun save(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = JSONArray()
        (listOfNotNull(queue.current) + queue.snapshot()).forEach {
            arr.put(JSONArray().put(it.song.id).put(it.song.title.take(100)).put(it.song.channel.take(40))
                .put(it.song.duration).put(it.singer).put((if (it.fromPhone) 1 else 0) or (if (it.duet) 2 else 0)))
        }
        prefs.edit().putString("q", arr.toString()).remove("queue").apply()
    }

    fun restore(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = runCatching { JSONArray(prefs.getString("q", null) ?: prefs.getString("queue", "[]")) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i)
            val o = if (r == null) arr.optJSONObject(i) ?: continue else null      // before 3.12: objects
            val id = r?.optString(0) ?: o!!.optString("i")
            val flags = r?.optInt(5) ?: ((if (o!!.optBoolean("p", false)) 1 else 0) or (if (o.optBoolean("du", false)) 2 else 0))
            val duet = flags and 2 != 0
            val raw = r?.optString(4) ?: o!!.optString("s")
            val name = (if (duet) raw.split(DUET_JOIN).let { duetName(it.getOrNull(0), it.getOrNull(1)) } else cleanName(raw)) ?: continue
            if (!isVideoId(id)) continue
            val song = if (r != null) Song(id, r.optString(1).take(150), r.optString(2).take(80), r.optString(3).take(10))
            else Song(id, o!!.optString("t").take(150), o.optString("c").take(80), o.optString("d").take(10))
            queue.add(QueueItem(song, name, flags and 1 != 0, duet = duet))
        }
    }
}
