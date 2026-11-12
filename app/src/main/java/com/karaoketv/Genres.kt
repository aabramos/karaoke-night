package com.karaoketv

import java.text.Normalizer

/**
 * A music genre a singer can collect ("first time singing rock" → 🎸 Rocker). [chip] is the short
 * label on the achievements screen. [searchWords] count when they're in the search or quick pick
 * that found the song ("rock classics"); [titleWords] count when they're in the video title: mostly
 * well-known artists, plus words only that genre's titles use ("opera", "disney", "fado").
 */
class Genre(
    val id: String, val icon: String, val chip: String, val title: String, val how: String,
    val searchWords: List<String>, val titleWords: List<String>,
)

/**
 * Guesses a song's genres from its YouTube title and how it was found. YouTube doesn't say what
 * genre a video is, so this is a best guess: a song can count for no genre or for a few.
 */
object Genres {
    val ALL = listOf(
        Genre("rock", "🎸", "Rock", "Rocker", "Sing a rock song",
            listOf("rock", "britpop", "indie", "deutschrock", "punk", "grunge", "j-rock"),
            listOf("queen", "ac/dc", "acdc", "bon jovi", "guns n roses", "the beatles", "beatles", "rolling stones", "nirvana",
                "oasis", "the killers", "foo fighters", "linkin park", "red hot chili peppers", "u2", "coldplay", "arctic monkeys", "aerosmith", "led zeppelin", "pink floyd", "the white stripes", "green day", "blink-182",
                "toto", "deep purple", "the clash", "the cure", "radiohead", "kings of leon",
                "the cranberries", "creedence clearwater revival", "eagles", "dire straits", "xutos", "xutos e pontapes",
                "gnr", "heroes del silencio", "soda stereo", "mana", "scorpions", "the doors", "the who", "bryan adams", "bruce springsteen", "fleetwood mac", "the police", "nickelback", "3 doors down", "the offspring")),
        Genre("pop", "🍭", "Pop", "Pop star", "Sing a pop song",
            listOf("pop", "j-pop", "sanremo", "variete", "schlager"),
            listOf("taylor swift", "ed sheeran", "adele", "dua lipa", "ariana grande", "katy perry", "lady gaga", "rihanna",
                "britney spears", "madonna", "michael jackson", "harry styles", "billie eilish", "olivia rodrigo",
                "bruno mars", "justin bieber", "miley cyrus", "the weeknd", "shawn mendes", "sia", "p!nk", "robbie williams", "backstreet boys", "spice girls", "one direction", "westlife", "abba", "a-ha",
                "whitney houston", "celine dion", "mariah carey", "elton john", "george michael", "wham", "kylie minogue",
                "sabrina carpenter", "lewis capaldi", "sam smith", "kelly clarkson", "avril lavigne", "jessie j", "rick astley")),
        Genre("dance", "🕺", "Disco", "Disco fever", "Sing a disco song",
            listOf("disco", "dance", "edm", "kizomba", "eurodance"),
            listOf("bee gees", "gloria gaynor", "village people", "boney m", "donna summer", "kool & the gang",
                "earth wind & fire", "chic", "abba dancing queen", "daft punk", "calvin harris", "david guetta", "avicii",
                "kc and the sunshine band", "sister sledge", "the weather girls", "haddaway", "vengaboys", "eiffel 65",
                "la bouche", "corona", "dr alban", "2 unlimited", "las ketchup", "los del rio")),
        Genre("hiphop", "🧢", "Hip-hop", "MC", "Sing a hip-hop song",
            listOf("hip hop", "hip-hop", "rap", "trap"),
            listOf("eminem", "drake", "kanye west", "jay-z", "2pac", "tupac", "notorious b.i.g", "snoop dogg", "dr. dre",
                "dr dre", "50 cent", "nicki minaj", "cardi b", "kendrick lamar", "post malone", "lil nas x", "outkast",
                "run-d.m.c", "beastie boys", "salt-n-pepa", "missy elliott", "macklemore", "the fugees", "coolio",
                "vanilla ice", "will smith", "black eyed peas", "pitbull", "doja cat", "travis scott", "mc hammer")),
        Genre("soul", "💜", "Soul", "Soulful", "Sing a soul or R&B song",
            listOf("r&b", "rnb", "soul", "motown", "gospel", "louvores", "funk"),
            listOf("aretha franklin", "stevie wonder", "marvin gaye", "otis redding", "ray charles", "james brown",
                "the temptations", "diana ross", "the supremes", "etta james", "beyonce", "destiny's child", "alicia keys",
                "usher", "amy winehouse", "john legend", "lionel richie", "tina turner", "bill withers", "al green",
                "sam cooke", "ben e. king", "ben e king", "toni braxton", "boyz ii men", "tlc", "lauryn hill", "sza",
                "the jackson 5", "jackson 5", "barry white", "luther vandross", "anita baker")),
        Genre("country", "🤠", "Country", "Cowboy", "Sing a country song",
            listOf("country", "rancheras", "rancheros", "corridos", "banda", "sertanejo"),
            listOf("dolly parton", "johnny cash", "shania twain", "garth brooks", "kenny rogers", "willie nelson",
                "carrie underwood", "luke combs", "morgan wallen", "john denver", "patsy cline", "tim mcgraw",
                "faith hill", "reba mcentire", "keith urban", "blake shelton", "lady a", "lady antebellum", "zac brown band",
                "florida georgia line", "brad paisley", "kacey musgraves", "chris stapleton", "billy ray cyrus",
                "tammy wynette", "loretta lynn", "alan jackson", "george strait", "the chicks", "dixie chicks")),
        Genre("latin", "💃", "Latin", "Latin heat", "Sing a Latin song",
            listOf("reggaeton", "salsa", "cumbia", "bachata", "rumba", "tango", "bolero", "boleros", "vallenato",
                "merengue", "latino", "latina", "cuarteto", "despecho", "flamenca", "flamenco", "mariachi"),
            listOf("shakira", "ricky martin", "enrique iglesias", "julio iglesias", "luis fonsi", "daddy yankee",
                "bad bunny", "j balvin", "maluma", "karol g", "marc anthony", "celia cruz", "gloria estefan",
                "jennifer lopez", "romeo santos", "juanes", "carlos vives", "thalia", "paulina rubio",
                "alejandro sanz", "camila cabello", "rosalia", "ozuna", "nicky jam", "becky g", "luis miguel",
                "vicente fernandez", "juan gabriel", "marco antonio solis", "gipsy kings", "camilo", "sebastian yatra")),
        Genre("metal", "🤘", "Metal", "Headbanger", "Sing a metal song",
            listOf("metal", "heavy metal", "hard rock"),
            listOf("metallica", "iron maiden", "black sabbath", "ozzy osbourne", "judas priest", "slipknot",
                "system of a down", "megadeth", "pantera", "motorhead", "evanescence", "rammstein",
                "nightwish", "within temptation", "avenged sevenfold", "disturbed", "korn", "limp bizkit", "def leppard",
                "motley crue", "whitesnake", "twisted sister", "bon scott", "sepultura", "moonspell", "powerwolf",
                "sabaton", "bring me the horizon", "five finger death punch")),
        Genre("musicals", "🎭", "Musicals", "Broadway star", "Sing a Broadway song",
            listOf("broadway", "musical", "musicals", "west end"),
            listOf("musical", "broadway", "hamilton", "les miserables", "phantom of the opera", "wicked", "mamma mia", "grease", "the greatest showman", "greatest showman", "cabaret",
                "evita", "jesus christ superstar", "hairspray", "dear evan hansen", "six the musical", "west side story",
                "the sound of music", "sound of music", "mary poppins", "matilda", "the rocky horror", "rocky horror",
                "dreamgirls", "la la land", "moulin rouge", "fiddler on the roof", "miss saigon")),
        Genre("movies", "🎬", "Movies", "Movie magic", "Sing a movie song",
            listOf("movie", "movies", "disney", "film", "soundtrack"),
            listOf("disney", "frozen", "moana", "vaiana", "encanto", "the lion king", "lion king", "aladdin",
                "the little mermaid", "little mermaid", "beauty and the beast", "tangled", "coco", "pocahontas",
                "mulan", "hercules", "tarzan", "toy story", "the jungle book", "jungle book", "pixar", "dreamworks",
                "shrek", "titanic", "james bond", "007", "top gun", "dirty dancing", "footloose", "flashdance",
                "the bodyguard", "bodyguard", "a star is born", "bohemian rhapsody movie", "barbie", "kpop demon hunters")),
        Genre("classical", "🎻", "Classical", "Maestro", "Sing opera or classical",
            listOf("opera", "classical", "classica", "lirica", "aria"),
            listOf("opera", "aria", "pavarotti", "andrea bocelli", "bocelli", "maria callas", "placido domingo",
                "jose carreras", "il divo", "il volo", "sarah brightman", "nessun dorma", "o sole mio", "ave maria",
                "con te partiro", "time to say goodbye", "habanera", "la traviata", "la boheme", "brindisi",
                "libiamo", "o mio babbino caro", "queen of the night", "der holle rache", "figaro", "la donna e mobile",
                "caruso", "the prayer", "panis angelicus", "hallelujah handel", "schubert", "mozart", "puccini", "verdi")),
        Genre("games", "🎮", "Games", "Game on", "Sing a game or anime song",
            listOf("anime", "video game", "videogame", "game", "gaming", "ost"),
            listOf("anime", "video game", "videogame", "game ost", "opening", "clair obscur", "expedition 33",
                "final fantasy", "kingdom hearts", "zelda", "super mario", "sonic the hedgehog", "pokemon", "undertale",
                "nier", "skyrim", "minecraft", "portal", "still alive", "want you gone", "genshin",
                "league of legends", "arcane", "the witcher", "toss a coin", "cuphead", "hollow knight", "metal gear", "silent hill", "naruto", "one piece", "attack on titan", "shingeki", "demon slayer",
                "kimetsu", "dragon ball", "sailor moon", "evangelion", "cruel angel", "my hero academia", "jujutsu kaisen",
                "spy x family", "kimi no na wa", "studio ghibli", "ghibli", "totoro", "spirited away",
                "doraemon", "pokemon theme", "fnaf", "five nights at freddy")),
        Genre("jazz", "🎺", "Jazz", "Jazz cat", "Sing a jazz or swing song",
            listOf("jazz", "swing", "big band", "crooner", "blues", "bossa nova"),
            listOf("frank sinatra", "sinatra", "ella fitzgerald", "louis armstrong", "nat king cole", "dean martin",
                "michael buble", "billie holiday", "nina simone", "tony bennett", "bobby darin", "norah jones",
                "diana krall", "etta james at last", "peggy lee", "sammy davis", "bing crosby", "chet baker",
                "fly me to the moon", "what a wonderful world", "feeling good", "my way", "new york new york",
                "dream a little dream", "summertime", "cheek to cheek", "the girl from ipanema", "garota de ipanema",
                "tom jobim", "joao gilberto", "jamie cullum", "harry connick")),
        Genre("reggae", "🌴", "Reggae", "Island vibes", "Sing a reggae song",
            listOf("reggae", "ska", "dancehall"),
            listOf("bob marley", "ub40", "jimmy cliff", "peter tosh", "shaggy", "sean paul", "inner circle",
                "ziggy marley", "toots and the maytals", "desmond dekker", "eddy grant", "maxi priest", "chaka demus",
                "damian marley", "matisyahu", "magic!", "johnny nash", "musical youth", "dawn penn", "buju banton")),
        Genre("asia", "🌸", "K-pop", "Idol", "Sing K-pop or J-pop",
            listOf("kpop", "k-pop", "j-pop", "jpop", "trot", "enka", "c-pop", "drama ost", "korean"),
            listOf("bts", "blackpink", "exo", "big bang", "bigbang", "psy", "gangnam style", "red velvet",
                "stray kids", "newjeans", "aespa", "itzy", "le sserafim", "girls generation",
                "snsd", "iu", "shinee", "txt", "ateez", "nct", "mamamoo", "(g)i-dle", "babymetal", "yoasobi",
                "kenshi yonezu", "jennie", "rose apt", "official hige dandism", "king gnu", "utada hikaru", "lisa gurenge", "golden huntrix")),
        Genre("pt", "🇵🇹", "Portuguese", "Saudade", "Sing a Portuguese song",
            listOf("fado", "pimba", "portuguesas", "portugueses", "portugues", "kizomba", "musica portuguesa"),
            listOf("fado", "amalia rodrigues", "amalia", "mariza", "ana moura", "dulce pontes", "carlos do carmo",
                "quim barreiros", "tony carreira", "rui veloso", "xutos & pontapes", "xutos e pontapes", "gnr",
                "delfins", "da weasel", "madredeus", "jorge palma", "sergio godinho", "ze amaro", "marco paulo",
                "roberto leal", "david carreira", "carolina deslandes", "diogo piçarra", "diogo picarra", "calema",
                "anselmo ralph", "salvador sobral", "amar pelos dois", "pedro abrunhosa", "ornatos violeta",
                "trovante", "jose cid", "paulo de carvalho", "rosinha", "emanuel", "nel monteiro", "ana malhoa",
                "agua de coco", "deolinda", "aurea", "ana bacalhau", "buraka som sistema", "slow j", "ivandro")),
        Genre("br", "🇧🇷", "Brazilian", "Samba spirit", "Sing a Brazilian song",
            listOf("brasileiras", "brasileira", "brasil", "sertanejo", "mpb", "samba", "pagode", "forro", "axe", "funk brasileiro"),
            listOf("anitta", "roberto carlos", "ivete sangalo", "marilia mendonca", "jorge & mateus", "jorge e mateus",
                "gusttavo lima", "tim maia", "caetano veloso", "gilberto gil", "djavan", "chico buarque", "elis regina",
                "ludmilla", "pabllo vittar", "zeze di camargo", "chitaozinho", "henrique e juliano", "maiara e maraisa",
                "luan santana", "michel telo", "ai se eu te pego", "legiao urbana", "skank", "paralamas", "titas",
                "cazuza", "marisa monte", "seu jorge", "alexandre pires", "zeca pagodinho", "thiaguinho", "joao gomes",
                "simone e simaria", "wesley safadao", "claudia leitte", "daniela mercury", "xuxa", "kid abelha")),
    )
    private val byId = ALL.associateBy { it.id }
    fun get(id: String) = byId[id]

    private val ACCENTS = Regex("\\p{Mn}+")
    private val OTHER = Regex("[^a-z0-9&!/+'.()-]+")
    private val DROPPED = Regex("[.'()]")
    private val SPACES = Regex("\\s+")

    /** Lower case, no accents, punctuation turned into spaces, padded: "Lumière!" → " lumiere! ". */
    private fun norm(s: String): String {
        val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(ACCENTS, "")
        return " " + plain.replace(OTHER, " ").replace(DROPPED, "").replace(SPACES, " ").trim() + " "
    }

    // Every keyword normalised once, the same way as the text it's looked for in
    private class Words(val search: List<String>, val title: List<String>)
    private val words by lazy { ALL.associate { g -> g.id to Words(g.searchWords.map(::norm), g.titleWords.map(::norm)) } }

    /**
     * The genres of a song, from its [title] and the [query] that found it (a typed search or a
     * quick pick such as "rock classics"; null if unknown, e.g. from "🕘 Played"). IDs in order.
     */
    fun detect(title: String, query: String?): List<String> {
        val t = norm(shortTitle(title).ifBlank { title })
        val q = query?.take(80)?.let { norm(it) }
        return ALL.filter { g ->
            val w = words.getValue(g.id)
            w.title.any { t.contains(it) } || (q != null && (w.search.any { q.contains(it) } || w.title.any { q.contains(it) }))
        }.map { it.id }
    }
}
