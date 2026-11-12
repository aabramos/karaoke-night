package com.karaoketv

import kotlin.random.Random

/**
 * "🎲 Surprise me": a random song for anyone stuck on what to sing. Mostly well-known karaoke
 * favourites everyone can sing along to; with a party country set, sometimes a local favourite or
 * a random song from one of the country's quick picks instead.
 */
object Surprise {

    /** A roll of the dice: what to search for, and how to show it ("Title · Artist"). */
    data class Roll(val query: String, val label: String, val named: Boolean)

    /** Songs that work at almost any party, as title to artist. */
    val WORLD: List<Pair<String, String>> = listOf(
        "Bohemian Rhapsody" to "Queen", "Don't Stop Me Now" to "Queen", "I Will Survive" to "Gloria Gaynor",
        "Dancing Queen" to "ABBA", "Mamma Mia" to "ABBA", "Waterloo" to "ABBA", "Total Eclipse of the Heart" to "Bonnie Tyler",
        "Livin' on a Prayer" to "Bon Jovi", "Sweet Caroline" to "Neil Diamond", "Don't Stop Believin'" to "Journey",
        "I Wanna Dance with Somebody" to "Whitney Houston", "I Will Always Love You" to "Whitney Houston",
        "Girls Just Want to Have Fun" to "Cyndi Lauper", "Like a Prayer" to "Madonna", "Billie Jean" to "Michael Jackson",
        "Beat It" to "Michael Jackson", "Wannabe" to "Spice Girls", "...Baby One More Time" to "Britney Spears",
        "Toxic" to "Britney Spears", "Oops!... I Did It Again" to "Britney Spears", "I Want It That Way" to "Backstreet Boys",
        "Everybody (Backstreet's Back)" to "Backstreet Boys", "My Heart Will Go On" to "Celine Dion", "Wonderwall" to "Oasis",
        "Mr. Brightside" to "The Killers", "Africa" to "Toto", "Take On Me" to "a-ha", "Summer of '69" to "Bryan Adams",
        "Hotel California" to "Eagles", "Sweet Child O' Mine" to "Guns N' Roses", "Wind of Change" to "Scorpions",
        "Smells Like Teen Spirit" to "Nirvana", "Zombie" to "The Cranberries", "Torn" to "Natalie Imbruglia",
        "Hey Jude" to "The Beatles", "Let It Be" to "The Beatles", "Yesterday" to "The Beatles",
        "Imagine" to "John Lennon", "Respect" to "Aretha Franklin", "Hit the Road Jack" to "Ray Charles",
        "Stand by Me" to "Ben E. King", "My Way" to "Frank Sinatra", "Fly Me to the Moon" to "Frank Sinatra",
        "Can't Help Falling in Love" to "Elvis Presley", "Jailhouse Rock" to "Elvis Presley", "Dancing in the Dark" to "Bruce Springsteen",
        "Rolling in the Deep" to "Adele", "Someone Like You" to "Adele", "Hello" to "Adele", "Shallow" to "Lady Gaga",
        "Poker Face" to "Lady Gaga", "Bad Romance" to "Lady Gaga", "Umbrella" to "Rihanna", "Diamonds" to "Rihanna",
        "Single Ladies" to "Beyoncé", "Crazy in Love" to "Beyoncé", "Halo" to "Beyoncé", "Shake It Off" to "Taylor Swift",
        "Love Story" to "Taylor Swift", "Firework" to "Katy Perry", "Roar" to "Katy Perry", "Shape of You" to "Ed Sheeran",
        "Perfect" to "Ed Sheeran", "Thinking Out Loud" to "Ed Sheeran", "Uptown Funk" to "Mark Ronson ft. Bruno Mars",
        "Just the Way You Are" to "Bruno Mars", "Locked Out of Heaven" to "Bruno Mars", "Happy" to "Pharrell Williams",
        "Despacito" to "Luis Fonsi", "Bailando" to "Enrique Iglesias", "Hips Don't Lie" to "Shakira",
        "Waka Waka" to "Shakira", "Livin' la Vida Loca" to "Ricky Martin", "La Bamba" to "Ritchie Valens",
        "Bésame Mucho" to "Consuelo Velázquez", "Volare" to "Gipsy Kings", "Con te partirò" to "Andrea Bocelli",
        "Let It Go" to "Frozen", "A Whole New World" to "Aladdin", "Hakuna Matata" to "The Lion King",
        "How Far I'll Go" to "Moana", "We Don't Talk About Bruno" to "Encanto", "Under the Sea" to "The Little Mermaid",
        "Grease Lightning" to "Grease", "You're the One That I Want" to "Grease", "Time of My Life" to "Dirty Dancing",
        "This Is Me" to "The Greatest Showman",
        "September" to "Earth, Wind & Fire", "YMCA" to "Village People", "Stayin' Alive" to "Bee Gees",
        "Night Fever" to "Bee Gees", "Le Freak" to "Chic", "Hot Stuff" to "Donna Summer", "It's Raining Men" to "The Weather Girls",
        "Believe" to "Cher", "Barbie Girl" to "Aqua", "Blue (Da Ba Dee)" to "Eiffel 65", "Macarena" to "Los del Río",
        "Gangnam Style" to "PSY", "Rasputin" to "Boney M.", "Daddy Cool" to "Boney M.", "Life Is Life" to "Opus",
        "Final Countdown" to "Europe", "Eye of the Tiger" to "Survivor", "We Will Rock You" to "Queen",
        "Highway to Hell" to "AC/DC", "Born to Be Wild" to "Steppenwolf", "Bad Guy" to "Billie Eilish",
        "Blinding Lights" to "The Weeknd", "Levitating" to "Dua Lipa", "Don't Start Now" to "Dua Lipa",
        "Flowers" to "Miley Cyrus", "Wrecking Ball" to "Miley Cyrus", "Party in the U.S.A." to "Miley Cyrus",
        "drivers license" to "Olivia Rodrigo", "As It Was" to "Harry Styles", "Someone You Loved" to "Lewis Capaldi",
        "Viva la Vida" to "Coldplay", "Yellow" to "Coldplay", "Counting Stars" to "OneRepublic",
        "Radioactive" to "Imagine Dragons", "Hey Ya!" to "OutKast", "Valerie" to "Amy Winehouse",
        "Rehab" to "Amy Winehouse", "Crazy" to "Gnarls Barkley", "Jolene" to "Dolly Parton", "9 to 5" to "Dolly Parton",
        "Islands in the Stream" to "Kenny Rogers & Dolly Parton", "Take Me Home, Country Roads" to "John Denver",
        "Ring of Fire" to "Johnny Cash", "Man! I Feel Like a Woman!" to "Shania Twain", "Achy Breaky Heart" to "Billy Ray Cyrus",
        "Don't Go Breaking My Heart" to "Elton John & Kiki Dee", "Your Song" to "Elton John", "Rocket Man" to "Elton John",
        "Purple Rain" to "Prince", "Kiss from a Rose" to "Seal", "Careless Whisper" to "George Michael",
        "Wake Me Up Before You Go-Go" to "Wham!", "Last Christmas" to "Wham!", "All I Want for Christmas Is You" to "Mariah Carey",
    ).distinct()

    /** Local favourites for a few countries, mixed in when that country is the party's. */
    private val LOCAL: Map<String, List<Pair<String, String>>> = mapOf(
        "PT" to listOf(
            "Amar pelos dois" to "Salvador Sobral", "Canção do mar" to "Dulce Pontes", "Uma casa portuguesa" to "Amália Rodrigues",
            "Barco negro" to "Amália Rodrigues", "Chuva" to "Mariza", "A minha casinha" to "Xutos & Pontapés",
            "Não sou o único" to "Xutos & Pontapés", "Porto Sentido" to "Rui Veloso", "Chico Fininho" to "Rui Veloso",
            "Anel de Rubi" to "Rui Veloso", "Lisboa menina e moça" to "Carlos do Carmo", "Grândola, Vila Morena" to "Zeca Afonso",
            "Desfolhada" to "Simone de Oliveira", "Amor de água fresca" to "Dina", "Chamar a música" to "Sara Tavares",
            "O Corpo é que Paga" to "António Variações", "Estou Além" to "António Variações",
            "Lisboa Não Sejas Francesa" to "Amália Rodrigues",
            "Bairro do Amor" to "Jorge Palma", "Deixa-me rir" to "Jorge Palma", "A Garagem da Vizinha" to "Quim Barreiros",
            "A Máquina" to "Amor Electro",
        ),
        "BR" to listOf(
            "Evidências" to "Chitãozinho & Xororó", "Garota de Ipanema" to "Tom Jobim", "Ai Se Eu Te Pego" to "Michel Teló",
            "Mas Que Nada" to "Jorge Ben Jor", "País Tropical" to "Jorge Ben Jor", "Exagerado" to "Cazuza",
            "Tempo Perdido" to "Legião Urbana", "Eduardo e Mônica" to "Legião Urbana", "Pais e Filhos" to "Legião Urbana",
            "Anna Júlia" to "Los Hermanos", "Sozinho" to "Caetano Veloso", "Garota Nacional" to "Skank",
            "Trem-Bala" to "Ana Vilela", "Fico Assim Sem Você" to "Adriana Calcanhotto", "Velha Infância" to "Tribalistas",
            "Asa Branca" to "Luiz Gonzaga", "Você Não Vale Nada" to "Calcinha Preta", "Ainda Gosto Dela" to "Skank",
            "Meu Abrigo" to "Melim", "Infiel" to "Marília Mendonça", "Show das Poderosas" to "Anitta",
        ),
        "ES" to listOf(
            "Bailando" to "Enrique Iglesias", "Macarena" to "Los del Río", "Mediterráneo" to "Joan Manuel Serrat",
            "La Flaca" to "Jarabe de Palo", "Corazón Partío" to "Alejandro Sanz", "Devuélveme a mi chica" to "Hombres G",
            "Sarandonga" to "Lolita", "Volando voy" to "Camarón", "Princesas" to "Pereza", "Un año más" to "Mecano",
        ),
        "FR" to listOf(
            "Les Champs-Élysées" to "Joe Dassin", "La Vie en rose" to "Édith Piaf", "Alexandrie Alexandra" to "Claude François",
            "Je veux" to "Zaz", "Formidable" to "Stromae", "Alors on danse" to "Stromae", "L'aventurier" to "Indochine",
            "Femme Like U" to "K.Maro", "Dernière danse" to "Indila", "Ne me quitte pas" to "Jacques Brel",
        ),
        "IT" to listOf(
            "Volare" to "Domenico Modugno", "Felicità" to "Al Bano & Romina Power", "L'italiano" to "Toto Cutugno",
            "Sarà perché ti amo" to "Ricchi e Poveri", "Azzurro" to "Adriano Celentano", "Gloria" to "Umberto Tozzi",
            "Ti amo" to "Umberto Tozzi", "Zitti e buoni" to "Måneskin", "Vita spericolata" to "Vasco Rossi",
        ),
    )

    /** How often a local song (or a local quick pick) is chosen when the party has a country. */
    private const val LOCAL_PERCENT = 45

    /**
     * Rolls the dice. [recent]: titles sung lately (lower case); a roll that matches one is tried
     * again a few times, so the dice don't suggest the song everyone just heard.
     */
    fun roll(country: Country, recent: Collection<String> = emptyList(), random: Random = Random.Default): Roll {
        var roll = rollOnce(country, random)
        repeat(6) {
            if (!roll.named || recent.none { it.contains(roll.label.substringBefore(" · ").lowercase()) }) return roll
            roll = rollOnce(country, random)
        }
        return roll
    }

    private fun rollOnce(country: Country, random: Random): Roll {
        if (!country.isInternational && random.nextInt(100) < LOCAL_PERCENT) {
            LOCAL[country.code]?.let { return named(it.random(random)) }
            // No list of our own for this country: a random song from one of its quick picks
            val picks = Countries.picks(country).take(6)
            if (picks.isNotEmpty()) picks.random(random).let { (label, query) -> return Roll(query, label, named = false) }
        }
        return named(WORLD.random(random))
    }

    private fun named(song: Pair<String, String>) = Roll("${song.second} ${song.first}", "${song.first} · ${song.second}", named = true)

    /**
     * The search results with the suggested song first: the best match for a named song, or a
     * random one of the top few for a quick pick ("Party songs" shouldn't always give the same one).
     */
    fun arrange(roll: Roll, songs: List<Song>, random: Random = Random.Default): List<Song> {
        if (songs.size < 2) return songs
        val i = if (roll.named) 0 else random.nextInt(minOf(8, songs.size))
        return listOf(songs[i]) + songs.filterIndexed { j, _ -> j != i }
    }

    /** A title flashing past while the dice roll. */
    fun teaser(random: Random = Random.Default): String = WORLD.random(random).first
}
