package com.karaoketv

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

data class Song(val id: String, val title: String, val channel: String, val duration: String) {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** [retries]: how many times another version of the song was tried because a video refused to play. */
data class QueueItem(
    val song: Song,
    val singer: String,
    val fromPhone: Boolean,
    val key: Long = nextKey(),
    val retries: Int = 0,
) {
    companion object {
        private var counter = 0L
        @Synchronized private fun nextKey() = ++counter
    }
}

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
fun isVideoId(s: String?) = s != null && VIDEO_ID.matches(s)

/** Trims a singer name, removes control characters and caps the length. Null if empty. */
fun cleanName(raw: String?): String? {
    if (raw == null) return null
    val s = raw.filter { !it.isISOControl() && it != '‮' && it != '‭' }.trim().replace(Regex("\\s+"), " ")
    return s.take(20).ifEmpty { null }
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
    listener: Listener,
) : PartyServer.Backend {

    fun interface Listener { fun onPhoneAdded(item: QueueItem) }

    private val listener = WeakReference(listener)
    private val main = Handler(Looper.getMainLooper())

    override fun searchJson(query: String): String {
        val arr = JSONArray()
        search.search(query).forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("dur", it.duration))
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
        return o.put("queue", arr).toString()
    }

    override fun add(videoId: String?, singer: String?): String? {
        if (videoId == null || !isVideoId(videoId)) return "That song can't be added."
        val song = search.lookup(videoId) ?: return "Search for the song first, then add it."
        val name = cleanName(singer) ?: return "Type your name first."
        val item = QueueItem(song, name, fromPhone = true)
        val err = queue.add(item)
        if (err == null) main.post { listener.get()?.onPhoneAdded(item) }
        return err
    }
}

/** Saves the queue (including whoever is on stage) so an accidental exit or crash doesn't lose it. */
object QueueStore {
    fun save(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = JSONArray()
        (listOfNotNull(queue.current) + queue.snapshot()).forEach {
            arr.put(JSONObject().put("i", it.song.id).put("t", it.song.title).put("c", it.song.channel)
                .put("d", it.song.duration).put("s", it.singer).put("p", it.fromPhone))
        }
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    fun restore(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i")
            val name = cleanName(o.optString("s")) ?: continue
            if (!isVideoId(id)) continue
            queue.add(QueueItem(Song(id, o.optString("t").take(150), o.optString("c").take(80), o.optString("d").take(10)), name, o.optBoolean("p", false)))
        }
    }
}
