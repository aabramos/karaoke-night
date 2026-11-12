package com.karaoketv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.Html
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

class SearchException(message: String) : Exception(message)

/**
 * Finds karaoke songs on YouTube in one of two ways:
 *  1. The official YouTube Data API, when a key is set in local.properties (reliable, 100 searches/day).
 *  2. Without a key: YouTube's own website search endpoint, the way open-source apps such as
 *     NewPipe do. No key or quota, but it isn't an official API, so YouTube may change it.
 * If the official quota runs out, it falls back to (2) automatically.
 */
class SongSearch(context: Context) {

    private val app = context.applicationContext
    private val apiKey = BuildConfig.YOUTUBE_API_KEY
    private val certSha1: String? by lazy { signingCertSha1() }
    // "search4": compact rows (3.12); folders from earlier versions are deleted
    private val cacheDir = File(app.cacheDir, "search4").apply {
        mkdirs()
        runCatching { listOf("search", "search2", "search3").forEach { File(app.cacheDir, it).deleteRecursively() } }
    }
    @Volatile private var officialBlockedUntil = 0L

    private val memCache = object : LinkedHashMap<String, List<Song>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>) = size > 30
    }
    /** Songs we've shown recently, by id. Phones may only queue songs found through search. */
    private val known = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > 600
    }

    /** Results are ranked for this country, in its language. International = the TV's own setting. */
    @Volatile var country: Country = Countries.INTERNATIONAL

    fun lookup(id: String): Song? = synchronized(known) { known[id] }

    /** Songs phones may add without searching first (the recently played ones). */
    fun remember(songs: List<Song>) = synchronized(known) { songs.forEach { known[it.id] = it } }

    /** Blocking; call off the main thread. */
    fun search(userQuery: String): List<Song> {
        val q = userQuery.trim().take(80)
        val query = if (q.contains("karaok", ignoreCase = true)) q else "$q karaoke"
        val where = country
        val key = where.code + "|" + query.lowercase(Locale.ROOT)   // each country has its own results

        synchronized(memCache) { memCache[key] }?.let { return it }

        // Searches are kept on the Shield for 24 h: the lobby appears instantly and API quota is saved
        val filtered = readDiskCache(key) ?: run {
            val found = if (apiKey.isNotBlank() && System.currentTimeMillis() > officialBlockedUntil) {
                try {
                    // The official API already leaves out videos that can't be embedded
                    officialSearch(query, where).filter(::isKaraoke).filter(::isPlayable).distinctBy { it.id }
                } catch (e: QuotaException) {
                    officialBlockedUntil = System.currentTimeMillis() + 6 * 3600_000L
                    webSearch(query, where)
                }
            } else {
                webSearch(query, where)
            }
            // Most-watched first (stable: equal counts keep YouTube's order)
            found.sortedByDescending { it.views }.take(MAX_RESULTS).also { if (it.isNotEmpty()) writeDiskCache(key, it) }
        }
        synchronized(memCache) { memCache[key] = filtered }
        // Apply what we've learned since this list was saved (videos that refused to play)
        val playable = filtered.filter(::isPlayable)
        synchronized(known) { playable.forEach { known[it.id] = it } }
        return playable
    }

    fun trimMemory() {
        synchronized(memCache) { memCache.clear() }
    }

    fun close() {
        synchronized(this) { checker?.shutdownNow(); checker = null }
    }

    // ------------------------------------------------------------------------------------
    // Can it play inside the app? Some owners turn off playback outside YouTube, and no app
    // setting gets around that. Each video is checked (oEmbed before showing, the player when
    // playing); only videos that refuse are hidden, and a channel only after repeated refusals.
    // ------------------------------------------------------------------------------------

    private val playPrefs = app.getSharedPreferences("playable2", Context.MODE_PRIVATE)
    /** Videos that refused to play (oldest first), and how often each channel refused. Guarded by badIds. */
    private val badIds = LinkedHashSet<String>()
    private val strikes = HashMap<String, Int>()
    private var checker: java.util.concurrent.ExecutorService? = null

    init {
        runCatching {
            (playPrefs.getString("ids", null) ?: "").split(',').filter(::isVideoId).forEach { badIds += it }
            val o = JSONObject(playPrefs.getString("strikes", null) ?: "{}")
            o.keys().forEach { strikes[it] = o.optInt(it) }
            // Older versions kept up to 3000: trim to today's limit once
            if (badIds.size > MAX_BAD_IDS) {
                badIds.toList().take(badIds.size - MAX_BAD_IDS).forEach { badIds.remove(it) }
                playPrefs.edit().putString("ids", badIds.joinToString(",")).apply()
            }
        }
        runCatching { app.deleteSharedPreferences("playable") }   // the first version's list, replaced by "playable2"
    }

    private fun channelKey(c: String) = c.lowercase(Locale.ROOT).trim()

    fun isPlayable(s: Song): Boolean {
        val ch = channelKey(s.channel)
        synchronized(badIds) {
            if (s.id in badIds) return false
            return ch.isEmpty() || (strikes[ch] ?: 0) < CHANNEL_STRIKES
        }
    }

    /** Remember a video that refused to play; a channel that refuses repeatedly is hidden entirely. */
    fun markUnplayable(s: Song, strikeChannel: Boolean = true) {
        synchronized(badIds) {
            badIds.remove(s.id)
            badIds.add(s.id)
            while (badIds.size > MAX_BAD_IDS) badIds.remove(badIds.first())
            val ch = channelKey(s.channel)
            if (strikeChannel && ch.isNotEmpty()) strikes[ch] = (strikes[ch] ?: 0) + 1
            val o = JSONObject()
            strikes.entries.sortedByDescending { it.value }.take(MAX_STRIKE_CHANNELS).forEach { o.put(it.key, it.value) }
            playPrefs.edit().putString("ids", badIds.joinToString(",")).putString("strikes", o.toString()).apply()
        }
        synchronized(known) { known.remove(s.id) }
    }

    /**
     * Asks YouTube's public oEmbed service about each result, in parallel. It answers 401 when the
     * owner has turned off playback outside YouTube. No answer counts as playable; if such a video
     * still refuses, the player finds another version of the song.
     */
    private fun dropUnembeddable(list: List<Song>): List<Song> {
        if (list.isEmpty()) return list
        val pool = synchronized(this) {
            // 8 checks at once while a search runs; the threads go away when idle (the Shield has little memory)
            checker ?: java.util.concurrent.ThreadPoolExecutor(8, 8, 20, java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue()) { r -> Thread(r, "embed-check").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .also { checker = it }
        }
        val futures = runCatching {
            pool.invokeAll(list.map { s -> java.util.concurrent.Callable { embedStatus(s.id) } }, 6, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return list
        val out = ArrayList<Song>(list.size)
        futures.forEachIndexed { i, f ->
            val code = if (f.isCancelled) 0 else runCatching { f.get() }.getOrDefault(0)
            // 401/403: owner blocks playback outside YouTube. 404: the video is gone.
            if (code == 401 || code == 403 || code == 404) markUnplayable(list[i], strikeChannel = false) else out += list[i]
        }
        return out.filter(::isPlayable)
    }

    private fun embedStatus(id: String): Int {
        val url = "https://www.youtube.com/oembed?format=json&url=" + enc("https://www.youtube.com/watch?v=$id")
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            val code = c.responseCode
            runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.skip(4096) } }
            code
        } finally {
            c.disconnect()
        }
    }

    /**
     * Blocking. Another karaoke video of the same song (used when a video refuses to play),
     * or null if no close match is found.
     */
    fun alternativeFor(song: Song, avoid: Set<String>): Song? {
        val name = songName(song.title)
        val words = significantWords(name)
        if (words.isEmpty()) return null
        // Strict on purpose: "Greatest Showman - This Is Me" must not stand in for "Greatest Showman - Never Enough"
        val needed = kotlin.math.ceil(words.size * 0.75).toInt()
        return search(name).firstOrNull { s ->
            s.id != song.id && s.id !in avoid && significantWords(s.title).count { it in words } >= needed
        }
    }

    // ------------------------------------------------------------------------------------
    // Official YouTube Data API v3
    // ------------------------------------------------------------------------------------

    private class QuotaException : Exception()

    private fun officialSearch(query: String, where: Country): List<Song> {
        val loc = Locale.getDefault()
        val region = if (where.isInternational) loc.country else where.code
        val language = if (where.isInternational) loc.language else where.relevanceLanguage
        val url = buildString {
            append("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=40")
            append("&videoEmbeddable=true&videoSyndicated=true&fields=items(id/videoId,snippet(title,channelTitle,liveBroadcastContent))")
            append("&q=").append(enc(query))
            if (region.length == 2) append("&regionCode=").append(region)
            if (language.isNotEmpty()) append("&relevanceLanguage=").append(enc(language))
            append("&key=").append(enc(apiKey))
        }
        val items = JSONObject(httpGet(url)).optJSONArray("items") ?: JSONArray()
        val partial = ArrayList<Song>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val id = it.optJSONObject("id")?.optString("videoId").orEmpty()
            val sn = it.optJSONObject("snippet") ?: continue
            if (!isVideoId(id) || sn.optString("liveBroadcastContent", "none") != "none") continue
            partial += Song(id, unescape(sn.optString("title")), unescape(sn.optString("channelTitle")), "")
        }
        if (partial.isEmpty()) return partial

        // One cheap extra call (1 unit) for durations and a final "can it be embedded?" check
        val ids = partial.joinToString(",") { it.id }
        val details = JSONObject(httpGet(
            "https://www.googleapis.com/youtube/v3/videos?part=contentDetails,status,statistics" +
                "&fields=items(id,contentDetails/duration,status/embeddable,statistics/viewCount)&id=${enc(ids)}&key=${enc(apiKey)}"
        )).optJSONArray("items") ?: JSONArray()
        val info = HashMap<String, Triple<String, Boolean, Long>>()
        for (i in 0 until details.length()) {
            val d = details.getJSONObject(i)
            info[d.optString("id")] = Triple(
                isoDuration(d.optJSONObject("contentDetails")?.optString("duration").orEmpty()),
                d.optJSONObject("status")?.optBoolean("embeddable", true) ?: true,
                d.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L
            )
        }
        return partial.mapNotNull { s ->
            val (dur, embeddable, views) = info[s.id] ?: return@mapNotNull s
            if (!embeddable || dur.isEmpty()) null else s.copy(duration = dur, views = views)
        }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            // Lets you restrict the API key to this app in Google Cloud (see README)
            setRequestProperty("X-Android-Package", app.packageName)
            certSha1?.let { setRequestProperty("X-Android-Cert", it) }
        }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
                val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                if (code == 403 && (reason.contains("quota", true) || reason.contains("dailyLimit", true))) {
                    throw QuotaException()
                }
                throw SearchException(unescape(err?.optString("message") ?: "HTTP $code"))
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------------------------
    // Key-less search through YouTube's website endpoint (streamed, low memory)
    // ------------------------------------------------------------------------------------

    private fun webSearch(query: String, where: Country): List<Song> {
        val loc = Locale.getDefault()
        val hl = if (where.isInternational) loc.language.ifEmpty { "en" } else where.youtubeHl.ifEmpty { "en" }
        val gl = if (where.isInternational) loc.country.ifEmpty { "US" } else where.code
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_CLIENT_VERSION)
                .put("hl", hl)
                .put("gl", gl)))
        val context = body.getJSONObject("context")
        val first = body
            .put("query", query)
            .put("params", "EgIQAQ%3D%3D")   // filter: videos only
            .toString()

        // Page through the results until enough of them can actually play here. Many karaoke
        // videos (most of KaraFun's, many of Sing King's) are blocked by their owners outside
        // YouTube, so each page is checked as it arrives, and paging goes on until WANTED
        // playable videos are found, or WEB_PAGES pages have been read.
        val out = LinkedHashMap<String, Song>()
        var request = first
        for (page in 0 until WEB_PAGES) {
            val next = arrayOfNulls<String>(1)
            // Only the first page may fail the search; a later page failing keeps what we have
            val got = if (page == 0) webPage(request, next) else runCatching { webPage(request, next) }.getOrNull() ?: break
            val candidates = got.filter { it.id !in out && isKaraoke(it) && isPlayable(it) }.distinctBy { it.id }
            dropUnembeddable(candidates).forEach { out[it.id] = it }
            if (out.size >= WANTED) break
            val token = next[0] ?: break
            request = JSONObject().put("context", context).put("continuation", token).toString()
        }
        return out.values.toList()
    }

    /** One page of results; [next] receives the token for the following page. */
    private fun webPage(body: String, next: Array<String?>): List<Song> {
        val c = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            setRequestProperty("X-YouTube-Client-Name", "1")
            setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw SearchException("YouTube search is unavailable right now (${c.responseCode})")
            val out = ArrayList<Song>()
            JsonReader(InputStreamReader(c.inputStream.buffered(), Charsets.UTF_8)).use { r -> walk(r, out, next, 0) }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Walks the JSON and pulls out every "videoRenderer", skipping everything else without storing it. */
    private fun walk(r: JsonReader, out: MutableList<Song>, next: Array<String?>, depth: Int) {
        if (depth > 60 || out.size >= 60) { r.skipValue(); return }
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name == "videoRenderer" && r.peek() == JsonToken.BEGIN_OBJECT) readVideo(r)?.let { out += it }
                    // YouTube's newer result layout (rolled out gradually, so a reply can have either)
                    else if (name == "lockupViewModel" && r.peek() == JsonToken.BEGIN_OBJECT)
                        (readJson(r, 0) as? JSONObject)?.let { lockupSong(it) }?.let { out += it }
                    else if (name == "continuationCommand" && r.peek() == JsonToken.BEGIN_OBJECT) readToken(r)?.let { next[0] = it }
                    else walk(r, out, next, depth + 1)
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) walk(r, out, next, depth + 1)
                r.endArray()
            }
            else -> r.skipValue()
        }
    }

    private fun readToken(r: JsonReader): String? {
        var token: String? = null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "token" && r.peek() == JsonToken.STRING) token = r.nextString() else r.skipValue()
        }
        r.endObject()
        return token?.takeIf { it.length in 10..2000 }
    }

    private fun readVideo(r: JsonReader): Song? {
        var id = ""; var title = ""; var channel = ""; var length = ""; var views = 0L
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "videoId" -> id = if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); "" }
                "title" -> title = readText(r)
                "ownerText" -> channel = readText(r)
                "longBylineText" -> { val t = readText(r); if (channel.isEmpty()) channel = t }
                "lengthText" -> length = readText(r)
                "viewCountText" -> views = parseViews(readText(r))
                else -> r.skipValue()
            }
        }
        r.endObject()
        // No length = live stream or premiere: skip
        return if (isVideoId(id) && title.isNotEmpty() && length.isNotEmpty()) Song(id, title, channel, length, views) else null
    }

    /** Reads one JSON value into org.json objects (used for single, small result items only). */
    private fun readJson(r: JsonReader, depth: Int): Any? {
        if (depth > 40) { r.skipValue(); return null }
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { o ->
                r.beginObject()
                while (r.hasNext()) { val k = r.nextName(); readJson(r, depth + 1)?.let { o.put(k, it) } }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { a ->
                r.beginArray()
                while (r.hasNext()) readJson(r, depth + 1)?.let { a.put(it) }
                r.endArray()
            }
            JsonToken.STRING -> r.nextString()
            JsonToken.NUMBER -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean()
            else -> { r.skipValue(); null }
        }
    }

    /** A video in the newer layout ("lockupViewModel"); null for playlists, channels, live streams. */
    private fun lockupSong(o: JSONObject): Song? = lockupToSong(o)

    /** Reads {"simpleText": ".."} or {"runs": [{"text": ".."}, ...]} */
    private fun readText(r: JsonReader): String {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return "" }
        val sb = StringBuilder()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "simpleText" -> if (r.peek() == JsonToken.STRING) sb.append(r.nextString()) else r.skipValue()
                "runs" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == "text" && r.peek() == JsonToken.STRING) sb.append(r.nextString()) else r.skipValue()
                        }
                        r.endObject()
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return sb.toString().take(150)
    }

    // ------------------------------------------------------------------------------------
    // Filtering, caching, helpers
    // ------------------------------------------------------------------------------------

    private fun isKaraoke(s: Song): Boolean {
        val title = s.title.lowercase(Locale.ROOT)
        val channel = s.channel.lowercase(Locale.ROOT)
        if (BLOCKED.any { title.contains(it) }) return false
        return KARAOKE_WORDS.any { title.contains(it) } || TRUSTED.any { channel.contains(it) }
    }

    private fun cacheFile(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, h)
    }

    /** Saved results: one short row per song, [ID, title, channel, duration, views]. */
    private fun readDiskCache(key: String): List<Song>? = runCatching {
        val f = cacheFile(key)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > DISK_CACHE_MS) return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull {
            val r = arr.optJSONArray(it) ?: return@mapNotNull null
            Song(r.getString(0), r.getString(1), r.getString(2), r.optString(3), r.optLong(4))
        }
    }.getOrNull()

    private fun writeDiskCache(key: String, list: List<Song>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONArray().put(it.id).put(it.title.take(120)).put(it.channel.take(60)).put(it.duration)
                    .apply { if (it.views > 0) put(it.views) })
            }
            cacheFile(key).writeText(arr.toString())
            // Keep the folder small: drop anything expired, then the oldest beyond the limit
            val files = cacheDir.listFiles() ?: return@runCatching
            val now = System.currentTimeMillis()
            val (old, fresh) = files.partition { now - it.lastModified() > DISK_CACHE_MS }
            old.forEach { it.delete() }
            if (fresh.size > DISK_CACHE_FILES) fresh.sortedBy { it.lastModified() }.take(fresh.size - DISK_CACHE_FILES).forEach { it.delete() }
        }
    }

    private fun signingCertSha1(): String? = runCatching {
        val pm = app.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    companion object {
        private const val WEB_CLIENT_VERSION = "2.20251020.01.00"
        /** Small on purpose: saved searches expire after a day, and refused videos are re-checked anyway. */
        private const val DISK_CACHE_MS = 24 * 3600_000L
        private const val DISK_CACHE_FILES = 40
        private const val MAX_BAD_IDS = 800
        private const val MAX_STRIKE_CHANNELS = 100

        private val KARAOKE_WORDS = listOf(
            "karaoke", "karaokê", "karaoké", "sing along", "sing-along", "instrumental with lyrics", "videoke",
            "カラオケ", "노래방", "караоке", "καραόκε", "كاريوكي", "คาราโอเกะ", "卡拉ok", "ktv", "伴奏",
        )
        private val TRUSTED = listOf("sing king", "karafun", "zoom karaoke", "stingray karaoke", "karaoke")
        private const val CHANNEL_STRIKES = 3
        /** Keyless search reads up to this many pages until it has WANTED playable karaoke videos. */
        private const val WEB_PAGES = 8
        private const val WANTED = 24
        private const val MAX_RESULTS = 30

        /**
         * View counts in any of YouTube's forms and languages: "1,234,567 views", "1.234.567
         * visualizações", "1.2M views", "1,2 mi de visualizações", "12 mil visualizaciones", "3,4 Mio.".
         */
        fun parseViews(text: String): Long {
            val m = COMPACT.find(text)
            if (m != null) {
                val number = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                val unit = m.groupValues[2].lowercase(Locale.ROOT)
                val mult = when (unit) {
                    in THOUSANDS -> 1e3
                    in MILLIONS -> 1e6
                    in BILLIONS -> 1e9
                    else -> 0.0
                }
                if (number != null && mult > 0) return (number * mult).toLong()
            }
            val digits = text.filter { it in '0'..'9' }
            return if (digits.isEmpty() || digits.length > 13) 0L else digits.toLong()
        }

        // "thousand / million / billion" as YouTube writes them in its main languages (lower case)
        private val THOUSANDS = setOf("k", "mil", "tsd", "тыс", "thousand", "mille", "tausend")
        private val MILLIONS = setOf("m", "mi", "mio", "mln", "млн", "million", "millions", "millionen", "milhão",
            "milhões", "millón", "millones", "milioni", "milione", "mn")
        private val BILLIONS = setOf("b", "bi", "bn", "mrd", "mld", "млрд", "billion", "billions", "milliard",
            "milliards", "milliarden", "mil milhões", "mil millones")
        // A number with one decimal part, then one of those words
        private val COMPACT = Regex(
            "(\\d+(?:[.,]\\d+)?)\\s*(" + (THOUSANDS + MILLIONS + BILLIONS).sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) } + ")\\.?(?![\\p{L}])",
            RegexOption.IGNORE_CASE
        )
        private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

        /**
         * The newer result layout:
         *   contentId, contentType = LOCKUP_CONTENT_TYPE_VIDEO,
         *   metadata.lockupMetadataViewModel.title.content,
         *   metadata.lockupMetadataViewModel.metadata.contentMetadataViewModel.metadataRows[].metadataParts[].text.content
         *     (first row: channel; later rows: views, age),
         *   contentImage …thumbnailBadgeViewModel.text = "4:13" (the duration badge on the thumbnail).
         */
        fun lockupToSong(o: JSONObject): Song? {
            val type = o.optString("contentType")
            if (type.isNotEmpty() && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
            val id = o.optString("contentId")
            if (!isVideoId(id)) return null
            val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
            val title = meta.optJSONObject("title")?.optString("content").orEmpty()
            if (title.isEmpty()) return null
            val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            val lines = ArrayList<List<String>>()
            if (rows != null) for (i in 0 until rows.length()) {
                val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                lines += (0 until parts.length()).mapNotNull {
                    parts.optJSONObject(it)?.optJSONObject("text")?.optString("content")?.takeIf { t -> t.isNotBlank() }
                }
            }
            val channel = lines.firstOrNull()?.firstOrNull().orEmpty()
            val views = lines.drop(1).flatten().firstOrNull { t -> t.any { it.isDigit() } }?.let { parseViews(it) } ?: 0L
            val duration = findBadge(o.optJSONObject("contentImage"), 0) ?: return null   // no duration: live or upcoming
            return Song(id, title, channel, duration, views)
        }

        /** The duration badge, wherever this version of the layout puts it. */
        private fun findBadge(o: Any?, depth: Int): String? {
            if (depth > 12) return null
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.trim()
                        ?.takeIf { DURATION.matches(it) }?.let { return it }
                    val keys = o.keys()
                    while (keys.hasNext()) findBadge(o.opt(keys.next()), depth + 1)?.let { return it }
                }
                is JSONArray -> for (i in 0 until o.length()) findBadge(o.opt(i), depth + 1)?.let { return it }
            }
            return null
        }

        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "and", "to", "in", "on", "de", "da", "do", "das", "dos", "e", "o", "os",
            "la", "el", "le", "les", "y", "feat", "ft", "karaoke", "karaokê", "version", "versión", "versão",
            "lyrics", "lyric", "instrumental", "official", "hd", "with", "sing", "along", "video", "audio", "key",
        )

        /** "Grease - Summer Nights (Karaoke Version) | Sing King" → "Grease - Summer Nights" */
        fun songName(title: String): String =
            title.substringBefore('|')
                .replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ")
                .replace(Regex("(?i)\\b(karaok[eê]|karafun|sing king|instrumental|lyrics?|version|official|hd)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '–', '—', ':', '·', '/')

        fun significantWords(text: String): Set<String> =
            text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}']+"))
                .map { it.trim('\'') }
                .filter { it.length >= 2 && it !in STOP_WORDS }
                .toSet()
        private val BLOCKED = listOf(
            "reaction", "react ", "tutorial", "fail", "funny", "compilation", "vlog", "review",
            "behind the scenes", "how to sing", "lesson", "#shorts"
        )

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        @Suppress("DEPRECATION")
        private fun unescape(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().take(150)

        /** "PT1H2M3S" → "1:02:03", "PT4M5S" → "4:05". Empty for live (P0D). */
        fun isoDuration(iso: String): String {
            val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(iso) ?: return ""
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].toIntOrNull() ?: 0
            if (h == 0 && min == 0 && s == 0) return ""
            return if (h > 0) "%d:%02d:%02d".format(h, min, s) else "%d:%02d".format(min, s)
        }
    }
}
