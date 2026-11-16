package com.karaoketv

// Songs and the names people type: what a song is, and tidying titles and names for the screen.

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

private val TITLE_NOISE = Regex(
    """\s*[(\[【][^)\]】]*\b(karaoke|lyrics?|instrumental|version|backing track|sing ?along|with vocals|hd|4k)\b[^)\]】]*[)\]】]""",
    RegexOption.IGNORE_CASE)
private val TITLE_SEPARATOR = Regex("""\s+[|｜]\s+""")
private val TITLE_TAIL = Regex("""(\s*[-–—:]\s*(karaoke|instrumental|lyrics?)\b.*$)|(\s+(karaoke|instrumental)(\s+version)?\s*$)""", RegexOption.IGNORE_CASE)
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

/**
 * A dedication ("for Bia") from what was typed, or null: empty, or the singer dedicating it to
 * themselves.
 */
fun dedicationFor(raw: String?, singer: String): String? =
    cleanName(raw)?.takeIf { d -> singer.split(DUET_JOIN).none { it.equals(d, ignoreCase = true) } }

/** A duet's display name from two raw names, or null if either is empty or they're the same person. */
fun duetName(a: String?, b: String?): String? {
    val x = cleanName(a) ?: return null
    val y = cleanName(b) ?: return null
    if (x.equals(y, ignoreCase = true)) return null
    return x + DUET_JOIN + y
}

/** How a duet's two names are joined everywhere: "Ana & Rui". */
const val DUET_JOIN = " & "
