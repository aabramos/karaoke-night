package com.karaoketv

import java.text.Collator
import java.util.Locale

/**
 * A country choice for the party. [code] is the ISO 3166 code ("" = International: no country,
 * the app's original mix). [language] is the main language for YouTube's ranking.
 */
data class Country(val code: String, val name: String, val flag: String, val language: String) {
    val isInternational get() = code.isEmpty()

    /** YouTube's interface language code ("hl"). */
    val youtubeHl: String
        get() = when {
            language.isEmpty() -> ""
            language == "zh" -> if (code in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            else -> language
        }

    /** The Data API's relevanceLanguage. */
    val relevanceLanguage: String
        get() = when {
            language == "zh" -> if (code in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            else -> language
        }
}

/**
 * Every country Android knows (names come from the system, so the list is complete and costs
 * nothing in the APK), plus quick picks: hand-written local favourites for the countries
 * where karaoke is big, and picks built from the country's name and language for all others.
 */
object Countries {

    val INTERNATIONAL = Country("", "International", "🌍", "")

    @Volatile private var cached: List<Country>? = null

    /** All countries, sorted by name. Built once (a few hundred small objects). */
    fun all(): List<Country> = cached ?: synchronized(this) {
        cached ?: run {
            val collator = Collator.getInstance(Locale.ENGLISH)
            Locale.getISOCountries()
                .mapNotNull { make(it) }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }
                .also { cached = it }
        }
    }

    fun byCode(code: String?): Country {
        if (code.isNullOrEmpty()) return INTERNATIONAL
        return make(code) ?: INTERNATIONAL
    }

    /** The country the TV is set to, if any. */
    fun device(): Country? = make(Locale.getDefault().country)

    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🏳"
        return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun make(code: String?): Country? {
        val c = code?.uppercase(Locale.ROOT) ?: return null
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        val name = Locale("", c).getDisplayCountry(Locale.ENGLISH)
        if (name.isBlank() || name == c) return null
        return Country(c, name, flag(c), LANG[c] ?: "en")
    }

    /** Matches the start of any word, ignoring accents: "ivo" finds "Côte d'Ivoire". */
    fun matches(country: Country, filter: String): Boolean {
        val f = fold(filter.trim())
        if (f.isEmpty()) return true
        if (country.code.equals(f, ignoreCase = true)) return true
        val words = fold(country.name).split(Regex("[^a-z0-9]+"))
        return fold(country.name).startsWith(f) || words.any { it.startsWith(f) }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    // ------------------------------------------------------------------------------------
    // Quick picks: (chip label, search). The search adds "karaoke" by itself.
    // ------------------------------------------------------------------------------------

    /** The original mix, used for International. */
    val INTERNATIONAL_PICKS = listOf(
        "🔥 Top hits" to "top hits",
        "💃 Pop" to "pop hits",
        "🎸 Rock" to "rock classics",
        "🕺 80s" to "80s hits",
        "📼 90s" to "90s hits",
        "💔 Ballads" to "love ballads",
        "👫 Duets" to "duets",
        "🎬 Movies" to "movie songs",
        "🍻 Party" to "party songs",
    )

    /** Added after a country's own picks: still ranked for that country by YouTube. */
    private val WORLD_TAIL = listOf(
        "💃 Pop" to "pop hits",
        "🎸 Rock" to "rock classics",
        "🕺 80s" to "80s hits",
        "👫 Duets" to "duets",
        "🎬 Movies" to "movie songs",
    )

    fun picks(c: Country): List<Pair<String, String>> {
        if (c.isInternational) return INTERNATIONAL_PICKS
        HAND[c.code]?.let { return it }
        val n = c.name
        val language = Locale(c.language).getDisplayLanguage(Locale.ENGLISH)
        val own = buildList {
            add("${c.flag} Top hits" to "$n top hits")
            add("🎶 Local favourites" to "$n songs")
            if (c.language != "en" && language.isNotBlank() && language != c.language) add("🗣 $language" to "$language songs")
            add("⭐ Classics" to "$n classic songs")
            add("💔 Love songs" to "$n love songs")
            add("🍻 Party" to "$n party songs")
        }
        return own + WORLD_TAIL
    }

    /** Local favourites, written in the local language where that finds better karaoke. */
    private val HAND: Map<String, List<Pair<String, String>>> = mapOf(
        "PT" to listOf(
            "🇵🇹 Top hits" to "músicas portuguesas", "🎻 Fado" to "fado", "🎉 Pimba" to "música pimba",
            "⭐ Clássicos" to "clássicos portugueses", "💃 Kizomba" to "kizomba", "💔 Românticas" to "músicas românticas portuguesas",
            "🇧🇷 Brasileiras" to "músicas brasileiras", "👫 Duetos" to "duetos portugueses", "🍻 Festa" to "músicas de festa portuguesas",
            "🎬 Disney" to "disney em português",
        ),
        "BR" to listOf(
            "🇧🇷 Top hits" to "músicas brasileiras", "🤠 Sertanejo" to "sertanejo", "🎼 MPB" to "mpb",
            "🥁 Pagode" to "pagode", "🪗 Forró" to "forró", "🔊 Funk" to "funk brasileiro",
            "💔 Românticas" to "músicas românticas brasileiras", "🙏 Gospel" to "louvores", "🕺 Anos 80" to "anos 80 brasil",
            "🎬 Disney" to "disney em português brasil",
        ),
        "US" to listOf(
            "🇺🇸 Top hits" to "top hits", "🤠 Country" to "country songs", "💃 Pop" to "pop hits",
            "🎸 Classic rock" to "classic rock", "🎤 R&B" to "r&b hits", "🧢 Hip hop" to "hip hop",
            "🕺 80s" to "80s hits", "📼 90s" to "90s hits", "🎭 Broadway" to "broadway musicals", "🎬 Movies" to "movie songs",
        ),
        "GB" to listOf(
            "🇬🇧 Top hits" to "uk top hits", "🎸 Britpop" to "britpop", "🤘 British rock" to "british rock classics",
            "🕺 80s" to "80s uk hits", "🎧 Indie" to "indie anthems", "🍺 Pub singalong" to "pub singalong songs",
            "🎭 West End" to "west end musicals", "💔 Ballads" to "love ballads", "👫 Duets" to "duets",
        ),
        "IE" to listOf(
            "🇮🇪 Top hits" to "irish hits", "☘️ Irish classics" to "irish songs", "🍺 Pub songs" to "irish pub songs",
            "🎸 Rock" to "irish rock", "💃 Pop" to "pop hits", "🕺 80s" to "80s hits", "👫 Duets" to "duets",
        ),
        "CA" to listOf(
            "🇨🇦 Top hits" to "canadian hits", "🤠 Country" to "canadian country", "⚜️ Québec" to "chansons québécoises",
            "🎸 Rock" to "canadian rock", "💃 Pop" to "pop hits", "🕺 80s" to "80s hits", "👫 Duets" to "duets", "🎬 Movies" to "movie songs",
        ),
        "AU" to listOf(
            "🇦🇺 Top hits" to "australian hits", "🎸 Aussie rock" to "aussie rock classics", "💃 Pop" to "pop hits",
            "🕺 80s" to "80s hits", "📼 90s" to "90s hits", "🍻 Party" to "party songs", "👫 Duets" to "duets",
        ),
        "ES" to listOf(
            "🇪🇸 Top hits" to "canciones españolas", "💃 Pop español" to "pop español", "🪭 Rumba" to "rumba flamenca",
            "🕺 Años 80" to "movida años 80", "💔 Baladas" to "baladas españolas", "🎸 Rock" to "rock español",
            "🌴 Reggaeton" to "reggaeton", "🍻 Fiesta" to "canciones de fiesta", "👫 Duetos" to "duetos en español",
        ),
        "MX" to listOf(
            "🇲🇽 Top hits" to "canciones mexicanas", "🤠 Rancheras" to "rancheras", "🎺 Banda" to "banda",
            "🎸 Corridos" to "corridos", "🌹 Boleros" to "boleros", "🪇 Cumbia" to "cumbia",
            "💃 Pop latino" to "pop latino", "💔 Baladas" to "baladas románticas", "🎸 Rock en español" to "rock en español",
        ),
        "AR" to listOf(
            "🇦🇷 Top hits" to "canciones argentinas", "🎸 Rock nacional" to "rock nacional argentino", "🪇 Cumbia" to "cumbia argentina",
            "💃 Tango" to "tango", "🎉 Cuarteto" to "cuarteto", "💔 Baladas" to "baladas románticas", "🌴 Reggaeton" to "reggaeton",
        ),
        "CO" to listOf(
            "🇨🇴 Top hits" to "canciones colombianas", "🪗 Vallenato" to "vallenato", "💃 Salsa" to "salsa",
            "🌴 Reggaeton" to "reggaeton", "🪇 Cumbia" to "cumbia colombiana", "💔 Despecho" to "música de despecho",
            "🎸 Rock en español" to "rock en español",
        ),
        "FR" to listOf(
            "🇫🇷 Top hits" to "chansons françaises", "🎙 Variété" to "variété française", "🕺 Années 80" to "années 80 français",
            "🎸 Rock" to "rock français", "💔 Amour" to "chansons d'amour françaises", "🎬 Disney" to "disney en français",
            "🍻 Soirée" to "chansons de soirée", "👫 Duos" to "duos français",
        ),
        "IT" to listOf(
            "🇮🇹 Top hits" to "canzoni italiane", "🎤 Sanremo" to "sanremo", "🕺 Anni 80" to "canzoni italiane anni 80",
            "💔 Amore" to "canzoni d'amore italiane", "🌋 Napoletane" to "canzoni napoletane", "🎸 Rock" to "rock italiano",
            "🎬 Disney" to "disney in italiano",
        ),
        "DE" to listOf(
            "🇩🇪 Top hits" to "deutsche hits", "🎉 Schlager" to "schlager", "🍻 Party" to "partyschlager",
            "🎸 Deutschrock" to "deutschrock", "🕺 NDW" to "neue deutsche welle", "💃 Pop" to "pop hits", "🎬 Disney" to "disney deutsch",
        ),
        "NL" to listOf(
            "🇳🇱 Top hits" to "nederlandstalige hits", "🎻 Levenslied" to "levenslied", "🍻 Feest" to "feestnummers",
            "⭐ Klassiekers" to "nederlandse klassiekers", "💃 Pop" to "pop hits", "🕺 80s" to "80s hits",
        ),
        "PH" to listOf(
            "🇵🇭 OPM" to "opm", "💔 OPM love songs" to "opm love songs", "🔥 Top hits" to "top hits",
            "🎤 Videoke" to "videoke songs", "🎶 Ballads" to "love ballads", "🕺 80s" to "80s hits", "👫 Duets" to "duets",
        ),
        "JP" to listOf(
            "🇯🇵 J-pop" to "j-pop", "🎌 Anime" to "anime songs", "🎎 Enka" to "演歌",
            "🔥 Top hits" to "人気曲", "💔 Ballads" to "バラード", "🎸 J-rock" to "j-rock",
        ),
        "KR" to listOf(
            "🇰🇷 K-pop" to "kpop", "💔 Ballads" to "korean ballad", "🎺 Trot" to "trot",
            "📺 Drama OST" to "korean drama ost", "🔥 Top hits" to "노래방 인기곡",
        ),
        "IN" to listOf(
            "🇮🇳 Bollywood" to "bollywood songs", "🎵 Hindi" to "hindi songs", "⭐ Old classics" to "old hindi songs",
            "💔 Romantic" to "romantic hindi songs", "🥁 Punjabi" to "punjabi songs", "🎶 Tamil" to "tamil songs",
            "🎶 Telugu" to "telugu songs", "🎶 Malayalam" to "malayalam songs",
        ),
    )

    /** Main language per country (ISO 639-1); anything missing uses English. */
    private val LANG: Map<String, String> = (
        "AD ca,AE ar,AF fa,AL sq,AM hy,AO pt,AR es,AT de,AW nl,AX sv,AZ az,BA bs,BD bn,BE nl,BF fr,BG bg,BH ar," +
        "BI fr,BJ fr,BL fr,BN ms,BO es,BQ nl,BR pt,BT dz,BV no,BY ru,CD fr,CF fr,CG fr,CH de,CI fr,CL es,CM fr," +
        "CN zh,CO es,CR es,CU es,CV pt,CW nl,CY el,CZ cs,DE de,DJ fr,DK da,DO es,DZ ar,EC es,EE et,EG ar,EH ar," +
        "ER ti,ES es,ET am,FI fi,FO fo,FR fr,GA fr,GE ka,GF fr,GL kl,GN fr,GP fr,GQ es,GR el,GT es,GW pt,HK zh," +
        "HN es,HR hr,HT fr,HU hu,ID id,IL he,IN hi,IQ ar,IR fa,IS is,IT it,JO ar,JP ja,KG ky,KH km,KM fr,KP ko," +
        "KR ko,KW ar,KZ kk,LA lo,LB ar,LI de,LK si,LT lt,LU fr,LV lv,LY ar,MA ar,MC fr,MD ro,ME sr,MF fr,MG mg," +
        "MK mk,ML fr,MM my,MN mn,MO zh,MQ fr,MR ar,MT mt,MV dv,MX es,MY ms,MZ pt,NC fr,NE fr,NI es,NL nl,NO no," +
        "NP ne,OM ar,PA es,PE es,PF fr,PK ur,PL pl,PM fr,PR es,PS ar,PT pt,PY es,QA ar,RE fr,RO ro,RS sr,RU ru," +
        "RW rw,SA ar,SD ar,SE sv,SI sl,SJ no,SK sk,SM it,SN fr,SO so,SR nl,ST pt,SV es,SX nl,SY ar,TD fr,TF fr," +
        "TG fr,TH th,TJ tg,TL pt,TM tk,TN ar,TR tr,TW zh,TZ sw,UA uk,UY es,UZ uz,VA it,VE es,VN vi,WF fr,YE ar,YT fr"
        ).split(',').associate { it.substringBefore(' ') to it.substringAfter(' ') }
}
