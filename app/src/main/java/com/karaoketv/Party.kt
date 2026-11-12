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
) {
    companion object {
        // Starts from the clock, so keys stay unique after the app restarts (phones remember theirs)
        private var counter = System.currentTimeMillis()
        @Synchronized private fun nextKey() = ++counter
        /** A restored song keeps its key; new keys must not reuse it. */
        @Synchronized fun noteKey(key: Long) { if (key > counter) counter = key }
    }
}

private val PHONE_ID = Regex("^[A-Za-z0-9]{16,64}$")

/**
 * A short fingerprint of a phone's random ID (null if it isn't one). Only the fingerprint is kept,
 * so the saved queue never holds the ID a phone uses to prove a song is its own.
 */
fun ownerTag(phone: String?): String? {
    if (phone == null || !PHONE_ID.matches(phone)) return null
    return java.security.MessageDigest.getInstance("SHA-256").digest(phone.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}

private val TITLE_NOISE = Regex(
    """\s*[(\[【][^)\]】]*\b(karaoke|lyrics?|instrumental|version|backing track|sing ?along|with vocals|hd|4k)\b[^)\]】]*[)\]】]""",
    RegexOption.IGNORE_CASE)
private val TITLE_SEPARATOR = Regex("""\s+[|｜]\s+""")
private val TITLE_TAIL = Regex("""\s*[-–—:]\s*(karaoke|instrumental|lyrics?)\b.*$""", RegexOption.IGNORE_CASE)
private val TITLE_QUALITY = Regex("""\s+(HD|HQ|4K)\s*$""", RegexOption.IGNORE_CASE)
private val TITLE_HEAD = Regex("""^\s*(karaoke|\[karaoke])\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)

/**
 * A song's name without the karaoke-channel extras, for small spaces such as the podium:
 * "Queen - Bohemian Rhapsody (Karaoke Version) | Sing King" → "Queen - Bohemian Rhapsody".
 * Falls back to the full title if nothing sensible is left.
 */
fun shortTitle(title: String): String {
    var t = title.replace(TITLE_NOISE, "")
    t = t.split(TITLE_SEPARATOR).first()
    t = t.replace(TITLE_TAIL, "").replace(TITLE_HEAD, "").replace(TITLE_QUALITY, "")
    t = t.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', ':', ',')
    return if (t.length >= 3) t else title.trim()
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
        fun onPhoneRemoved(item: QueueItem)
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
        // "k": each song's key, so the phone that added a song can offer to remove it
        queue.snapshot().forEach { arr.put(JSONObject().put("singer", it.singer).put("title", it.song.title).put("k", it.key)) }
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
            board.tonightBest(3).forEach { a.put(JSONObject().put("name", it.name).put("score", it.score).put("song", shortTitle(it.song).take(60))) }
        })
        board.tonightBest(1, duet = true).firstOrNull()?.let { o.put("duet", JSONObject().put("name", it.name).put("score", it.score).put("song", shortTitle(it.song).take(60))) }
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

    override fun add(videoId: String?, singer: String?, partner: String?, phone: String?, from: String?): PartyServer.Added {
        fun fail(msg: String) = PartyServer.Added.failed(msg)
        if (videoId == null || !isVideoId(videoId)) return fail("That song can't be added.")
        val song = search.lookup(videoId) ?: return fail("Search for the song first, then add it.")
        val solo = cleanName(singer) ?: return fail("Type your name first.")
        val duet = !partner.isNullOrBlank()
        val name = if (duet) duetName(solo, partner) ?: return fail("Type a different name for your duet partner.") else solo
        val item = QueueItem(song, name, fromPhone = true, duet = duet, owner = ownerTag(phone),
            genres = Genres.detect(song.title, from?.take(80)))
        val err = queue.add(item)
        if (err != null) return fail(err)
        invalidate()
        board.rememberPlayers(name.split(DUET_JOIN))
        main.post { listener.get()?.onPhoneAdded(item) }
        return PartyServer.Added.ok(item.key)
    }

    override fun remove(key: String?, phone: String?): String? {
        val k = key?.toLongOrNull() ?: return "That song can't be removed."
        val owner = ownerTag(phone) ?: return "Only the phone that added a song can remove it."
        val removed = queue.removeOwned(k, owner)
            ?: return if (queue.contains(k)) "Only the phone that added a song can remove it."
            else "That song isn't waiting in the queue any more."
        invalidate()
        main.post { listener.get()?.onPhoneRemoved(removed) }
        return null
    }
}

private const val STATE_CACHE_MS = 1000L

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
                .put(it.song.duration).put(it.singer).put((if (it.fromPhone) 1 else 0) or (if (it.duet) 2 else 0))
                .put(it.key).put(it.owner ?: "").apply { if (it.genres.isNotEmpty()) put(it.genres.joinToString(",")) })
        }
        prefs.edit().putString("q", arr.toString()).remove("queue").apply()
    }

    fun restore(prefs: android.content.SharedPreferences, queue: PartyQueue) {
        val arr = runCatching { JSONArray(prefs.getString("q", null) ?: prefs.getString("queue", null) ?: "[]") }.getOrNull() ?: return
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
            val key = r?.optLong(6) ?: 0L
            val owner = r?.optString(7)?.takeIf { it.length == 16 }
            if (key > 0) QueueItem.noteKey(key)
            val genres = r?.optString(8).orEmpty().split(',').filter { Genres.get(it) != null }
            queue.add(if (key > 0) QueueItem(song, name, flags and 1 != 0, key = key, duet = duet, owner = owner, genres = genres)
                      else QueueItem(song, name, flags and 1 != 0, duet = duet, genres = Genres.detect(song.title, null)))
        }
    }
}
