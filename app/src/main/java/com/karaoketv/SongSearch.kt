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
    private val cacheDir = File(app.cacheDir, "search").apply { mkdirs() }
    @Volatile private var officialBlockedUntil = 0L

    private val memCache = object : LinkedHashMap<String, List<Song>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Song>>) = size > 30
    }
    /** Songs we've shown recently, by id. Phones may only queue songs found through search. */
    private val known = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > 600
    }

    val usesOfficialApi: Boolean get() = apiKey.isNotBlank()

    fun lookup(id: String): Song? = synchronized(known) { known[id] }

    /** Blocking; call off the main thread. */
    fun search(userQuery: String): List<Song> {
        val q = userQuery.trim().take(80)
        val query = if (q.contains("karaok", ignoreCase = true)) q else "$q karaoke"
        val key = query.lowercase(Locale.ROOT)

        synchronized(memCache) { memCache[key] }?.let { return it }

        // Searches are kept on the Shield for 24 h: the lobby appears instantly and API quota is saved
        val filtered = readDiskCache(key) ?: run {
            var official = false
            val raw = if (apiKey.isNotBlank() && System.currentTimeMillis() > officialBlockedUntil) {
                try {
                    officialSearch(query).also { official = true }
                } catch (e: QuotaException) {
                    officialBlockedUntil = System.currentTimeMillis() + 6 * 3600_000L
                    webSearch(query)
                }
            } else {
                webSearch(query)
            }
            val karaoke = raw.filter(::isKaraoke).filter(::isPlayable).distinctBy { it.id }.take(30)
            // The official API already left out videos that can't be embedded
            (if (official) karaoke else dropUnembeddable(karaoke)).also { if (it.isNotEmpty()) writeDiskCache(key, it) }
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
    // Can it play inside the app? Some owners (for example Sing King and KaraFun) turn off
    // playback outside YouTube. No app setting gets around that, so those videos are hidden.
    // ------------------------------------------------------------------------------------

    private val playPrefs = app.getSharedPreferences("playable", Context.MODE_PRIVATE)
    /** Videos that refused to play (oldest first), and how often each channel refused. Guarded by badIds. */
    private val badIds = LinkedHashSet<String>()
    private val strikes = HashMap<String, Int>()
    private var checker: java.util.concurrent.ExecutorService? = null

    init {
        runCatching {
            playPrefs.getString("ids", "")!!.split(',').filter(::isVideoId).forEach { badIds += it }
            val o = JSONObject(playPrefs.getString("strikes", "{}")!!)
            o.keys().forEach { strikes[it] = o.optInt(it) }
        }
    }

    private fun channelKey(c: String) = c.lowercase(Locale.ROOT).trim()

    fun isPlayable(s: Song): Boolean {
        val ch = channelKey(s.channel)
        if (NO_EMBED_CHANNELS.any { ch.contains(it) }) return false
        synchronized(badIds) {
            if (s.id in badIds) return false
            return ch.isEmpty() || (strikes[ch] ?: 0) < CHANNEL_STRIKES
        }
    }

    /** Remember a video that refused to play; a channel that refuses repeatedly is hidden entirely. */
    fun markUnplayable(s: Song) {
        synchronized(badIds) {
            badIds.remove(s.id)
            badIds.add(s.id)
            while (badIds.size > 400) badIds.remove(badIds.first())
            val ch = channelKey(s.channel)
            if (ch.isNotEmpty()) strikes[ch] = (strikes[ch] ?: 0) + 1
            val o = JSONObject()
            strikes.entries.sortedByDescending { it.value }.take(200).forEach { o.put(it.key, it.value) }
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
            checker ?: java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
                Thread(r, "embed-check").apply { isDaemon = true }
            }.also { checker = it }
        }
        val futures = runCatching {
            pool.invokeAll(list.map { s -> java.util.concurrent.Callable { embedStatus(s.id) } }, 6, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return list
        val out = ArrayList<Song>(list.size)
        futures.forEachIndexed { i, f ->
            val code = if (f.isCancelled) 0 else runCatching { f.get() }.getOrDefault(0)
            if (code == 401 || code == 403) markUnplayable(list[i]) else out += list[i]
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

    private fun officialSearch(query: String): List<Song> {
        val loc = Locale.getDefault()
        val url = buildString {
            append("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=40")
            append("&videoEmbeddable=true&videoSyndicated=true&fields=items(id/videoId,snippet(title,channelTitle,liveBroadcastContent))")
            append("&q=").append(enc(query))
            if (loc.country.length == 2) append("&regionCode=").append(loc.country)
            if (loc.language.isNotEmpty()) append("&relevanceLanguage=").append(loc.language)
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
            "https://www.googleapis.com/youtube/v3/videos?part=contentDetails,status" +
                "&fields=items(id,contentDetails/duration,status/embeddable)&id=${enc(ids)}&key=${enc(apiKey)}"
        )).optJSONArray("items") ?: JSONArray()
        val info = HashMap<String, Pair<String, Boolean>>()
        for (i in 0 until details.length()) {
            val d = details.getJSONObject(i)
            info[d.optString("id")] = Pair(
                isoDuration(d.optJSONObject("contentDetails")?.optString("duration").orEmpty()),
                d.optJSONObject("status")?.optBoolean("embeddable", true) ?: true
            )
        }
        return partial.mapNotNull { s ->
            val (dur, embeddable) = info[s.id] ?: return@mapNotNull s
            if (!embeddable || dur.isEmpty()) null else s.copy(duration = dur)
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

    private fun webSearch(query: String): List<Song> {
        val loc = Locale.getDefault()
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_CLIENT_VERSION)
                .put("hl", loc.language.ifEmpty { "en" })
                .put("gl", loc.country.ifEmpty { "US" })))
            .put("query", query)
            .put("params", "EgIQAQ%3D%3D")   // filter: videos only
            .toString()

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
            JsonReader(InputStreamReader(c.inputStream.buffered(), Charsets.UTF_8)).use { r -> walk(r, out, 0) }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Walks the JSON and pulls out every "videoRenderer", skipping everything else without storing it. */
    private fun walk(r: JsonReader, out: MutableList<Song>, depth: Int) {
        if (depth > 60 || out.size >= 60) { r.skipValue(); return }
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name == "videoRenderer" && r.peek() == JsonToken.BEGIN_OBJECT) readVideo(r)?.let { out += it }
                    else walk(r, out, depth + 1)
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) walk(r, out, depth + 1)
                r.endArray()
            }
            else -> r.skipValue()
        }
    }

    private fun readVideo(r: JsonReader): Song? {
        var id = ""; var title = ""; var channel = ""; var length = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "videoId" -> id = if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); "" }
                "title" -> title = readText(r)
                "ownerText" -> channel = readText(r)
                "longBylineText" -> { val t = readText(r); if (channel.isEmpty()) channel = t }
                "lengthText" -> length = readText(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        // No length = live stream or premiere: skip
        return if (isVideoId(id) && title.isNotEmpty() && length.isNotEmpty()) Song(id, title, channel, length) else null
    }

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

    private fun readDiskCache(key: String): List<Song>? = runCatching {
        val f = cacheFile(key)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L) return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Song(o.getString("i"), o.getString("t"), o.getString("c"), o.optString("d"))
        }
    }.getOrNull()

    private fun writeDiskCache(key: String, list: List<Song>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("i", it.id).put("t", it.title).put("c", it.channel).put("d", it.duration)) }
            cacheFile(key).writeText(arr.toString())
            // keep the cache folder small
            val files = cacheDir.listFiles() ?: return@runCatching
            if (files.size > 60) files.sortedBy { it.lastModified() }.take(files.size - 60).forEach { it.delete() }
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

        private val KARAOKE_WORDS = listOf("karaoke", "karaokê", "sing along", "sing-along", "instrumental with lyrics")
        private val TRUSTED = listOf("zoom karaoke", "stingray karaoke", "karaoke")
        /** Channels whose videos refuse to play outside YouTube (reported on the Shield). */
        private val NO_EMBED_CHANNELS = listOf("sing king", "karafun")
        private const val CHANNEL_STRIKES = 3

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
