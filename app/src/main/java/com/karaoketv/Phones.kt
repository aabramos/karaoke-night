package com.karaoketv

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

// The guests' phones: what the party page asks the TV for, their cheers, and whose phone is whose.

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
        /** A phone's search failed for lack of internet: the TV checks its connection. */
        fun onOffline()
        /** A guest cheered from their phone (only sent while a song is playing): [REACTIONS] index and the phone's fingerprint. */
        fun onReaction(index: Int, owner: String)
    }

    /** Set by the screen: false while the TV can't reach YouTube (phones are told; searches fail fast). */
    @Volatile var online = true
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Set by the screen: phones can cheer only while a song is actually playing. */
    @Volatile var live = false
        set(v) { if (field != v) { field = v; invalidate() } }

    // When each phone last cheered (by fingerprint), to space them out
    private val lastCheer = HashMap<String, Long>()

    private val listener = WeakReference(listener)
    private val main = Handler(Looper.getMainLooper())

    override fun searchJson(query: String): String {
        val arr = JSONArray()
        guarded { search.search(query) }.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("dur", it.duration)
                .put("views", viewsLabel(it.views)))
        }
        return arr.toString()
    }

    /**
     * Runs a YouTube search for a phone. Failures become messages for the guest; one for lack of
     * internet also makes the TV check its connection.
     */
    private fun <T> guarded(block: () -> T): T {
        if (!online) throw PartyServer.GuestError(NO_INTERNET)
        return try {
            block()
        } catch (e: SearchException) {
            throw PartyServer.GuestError(e.message ?: "Search failed. Try again.")
        } catch (e: Exception) {
            main.post { listener.get()?.onOffline() }
            throw PartyServer.GuestError(NO_INTERNET)
        }
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
        queue.current?.let { o.put("now", JSONObject().put("singer", it.singer).put("title", it.song.title).put("k", it.key)) }
        if (!online) o.put("offline", true)
        // While a song plays, the phones show these cheer buttons
        if (live && queue.current != null) o.put("react", JSONArray().also { a -> REACTIONS.forEach { a.put(it) } })
        val arr = JSONArray()
        // "k": each song's key, so the phone that added a song can offer to remove it
        queue.snapshot().forEach { q ->
            arr.put(JSONObject().put("singer", q.singer).put("title", q.song.title).put("k", q.key).apply { q.dedication?.let { put("for", it) } })
        }
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

    override fun add(videoId: String?, singer: String?, partner: String?, phone: String?, from: String?, forWho: String?): PartyServer.Added {
        fun fail(msg: String) = PartyServer.Added.failed(msg)
        if (videoId == null || !isVideoId(videoId)) return fail("That song can't be added.")
        val song = search.lookup(videoId) ?: return fail("Search for the song first, then add it.")
        val solo = cleanName(singer) ?: return fail("Type your name first.")
        val duet = !partner.isNullOrBlank()
        val name = if (duet) duetName(solo, partner) ?: return fail("Type a different name for your duet partner.") else solo
        val item = QueueItem(song, name, fromPhone = true, duet = duet, owner = ownerTag(phone),
            genres = Genres.detect(song.title, from?.take(80)), dedication = dedicationFor(forWho, name))
        val err = queue.add(item)
        if (err != null) return fail(err)
        invalidate()
        board.rememberPlayers(name.split(DUET_JOIN))
        main.post { listener.get()?.onPhoneAdded(item) }
        return PartyServer.Added.ok(item.key)
    }

    override fun react(emoji: String?, phone: String?): String? {
        val index = emoji?.toIntOrNull()?.takeIf { it in REACTIONS.indices } ?: return "That cheer isn't available."
        if (!live) return "Cheers work while someone is singing."
        val owner = ownerTag(phone) ?: return "Reload the page and try again."
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastCheer) {
            val last = lastCheer[owner]
            if (last != null && now - last < CHEER_GAP_MS) return "Easy there! One cheer every few seconds."
            if (lastCheer.size >= 300) {
                val old = lastCheer.entries.iterator()
                while (old.hasNext()) if (now - old.next().value > 60_000) old.remove()
                if (lastCheer.size >= 300) return "Lots of cheering going on! Try again in a moment."
            }
            lastCheer[owner] = now
        }
        main.post { listener.get()?.onReaction(index, owner) }
        return null
    }

    override fun surpriseJson(): String {
        val recent = history.all().map { it.song.title.lowercase() }
        val roll = Surprise.roll(search.country, recent)
        val songs = Surprise.arrange(roll, guarded { search.search(roll.query) })
        val arr = JSONArray()
        songs.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel).put("dur", it.duration)
                .put("views", viewsLabel(it.views)))
        }
        return JSONObject().put("label", roll.label).put("query", roll.query).put("songs", arr).toString()
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

private const val NO_INTERNET = "📡 The TV can't reach YouTube right now. Try again when its internet is back."

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

/**
 * Who each phone queues songs for tonight (by fingerprint): the people in every one of its songs.
 * A phone that only queues songs with Ana in them is Ana's own, so its emojis don't count while
 * Ana sings (no cheering yourself). A phone a small party passes around (songs for Ana, then for
 * Rui) is nobody's in particular, so it always counts.
 */
class PhoneOwners(private val max: Int = 300) {
    private val people = HashMap<String, Set<String>>()

    private fun peopleIn(item: QueueItem) = item.singer.split(DUET_JOIN).map { it.trim().lowercase() }.toSet()

    /** A phone queued [item] (songs from the TV have no phone and are ignored). */
    fun note(item: QueueItem) {
        val phone = item.owner ?: return
        if (phone !in people && people.size >= max) return
        val these = peopleIn(item)
        people[phone] = people[phone]?.intersect(these) ?: these
    }

    /** Whether [phone] belongs to someone singing [item]: it queued it, and all its songs have one of them in them. */
    fun isSingers(phone: String, item: QueueItem): Boolean {
        if (phone != item.owner) return false
        val theirs = people[phone] ?: return true
        return peopleIn(item).any { it in theirs }
    }
}

/** The cheers guests can send from their phones (the phone sends the number). */
val REACTIONS = listOf("🔥", "😍", "👏", "🎉", "😂", "😴", "🍅")   // best to worst, as on the phones

/**
 * The secret behind the cheers: each phone's emojis, averaged, are a vote that pulls
 * the score towards what the room thought (see [Judge.crowd]). Same order as [REACTIONS]. Nobody is told.
 */
val REACTION_VOTES = intArrayOf(2, 2, 1, 1, 1, -1, -2)   // 😂 counts as fun, like 👏 and 🎉

/** A phone can cheer once every this many milliseconds (the page itself waits a little longer). */
const val CHEER_GAP_MS = 2500L
