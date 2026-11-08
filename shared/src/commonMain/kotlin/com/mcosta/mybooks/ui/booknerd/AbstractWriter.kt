package com.mcosta.mybooks.ui.booknerd

/** The genres BookNerd writes abstracts for (its chips: Romance, Mystery, Fantasy, Science fiction, …). */
enum class Genre { ROMANCE, MYSTERY, FANTASY, SCIENCE_FICTION, HISTORICAL, THRILLER, DRAMA, COMEDY }

/** The tones (chips: Light, Dark, Moving, Funny, Suspenseful). */
enum class Tone { LIGHT, DARK, MOVING, FUNNY, SUSPENSEFUL }

/** The five answers an abstract is written from. */
data class IdeaAnswers(val genre: Genre, val character: String, val setting: String, val conflict: String, val tone: Tone)

/** Longest an answer is kept (cut at a word), so a long answer can't swamp the abstract. */
const val ANSWER_MAX = 120

private const val MIN_WORDS = 120
private const val MAX_WORDS = 180

/** A chip label or typed word as a genre ("ficção científica" → SCIENCE_FICTION); DRAMA when unsure. */
fun genreOf(text: String): Genre {
    val t = fold(text)
    return when {
        listOf("science", "scifi", "sci-fi", "ficcao cientifica", "cientifica", "futur").any { it in t } -> Genre.SCIENCE_FICTION
        listOf("romance", "romant", "amor", "love").any { it in t } -> Genre.ROMANCE
        listOf("myster", "mister", "policial", "crime", "detect").any { it in t } -> Genre.MYSTERY
        listOf("fantas", "magia", "magic").any { it in t } -> Genre.FANTASY
        listOf("historic", "historical", "epoca").any { it in t } -> Genre.HISTORICAL
        listOf("thriller", "suspense", "espionag").any { it in t } -> Genre.THRILLER
        listOf("comed", "humor", "humour", "funny").any { it in t } -> Genre.COMEDY
        else -> Genre.DRAMA
    }
}

/** A chip label or typed word as a tone ("Escuro" → DARK); MOVING when unsure. */
fun toneOf(text: String): Tone {
    val t = fold(text)
    return when {
        listOf("light", "leve", "alegre").any { it in t } -> Tone.LIGHT
        listOf("dark", "escuro", "sombrio", "negro").any { it in t } -> Tone.DARK
        listOf("funny", "divertid", "engracad", "comic").any { it in t } -> Tone.FUNNY
        listOf("suspens", "tenso", "tense", "tension").any { it in t } -> Tone.SUSPENSEFUL
        listOf("moving", "comovente", "emoti", "touching").any { it in t } -> Tone.MOVING
        else -> Tone.MOVING
    }
}

/** An answer as used in the text: trimmed, without trailing full stops, at most [ANSWER_MAX] characters (at a word). */
fun cutAnswer(text: String): String {
    val clean = text.trim().trimEnd('.', '!', '?', ';', ',', ' ')
    if (clean.length <= ANSWER_MAX) return clean
    val cut = clean.take(ANSWER_MAX)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd(',', ' ')
}

private val PT_PREPOSITIONS = setOf(
    "em", "no", "na", "nos", "nas", "num", "numa", "nuns", "numas", "durante", "antes", "depois", "entre", "perto",
    "junto", "algures", "sob", "dentro", "fora", "ao", "aos", "à", "às", "pelo", "pela", "pelos", "pelas", "desde",
)
/** A Portuguese place that starts with an article takes "em" contracted into it: "o Porto" → "no Porto". */
private val PT_ARTICLES = mapOf("o" to "no", "a" to "na", "os" to "nos", "as" to "nas", "um" to "num", "uma" to "numa")
private val EN_PREPOSITIONS = setOf(
    "in", "at", "on", "during", "before", "after", "between", "near", "under", "inside", "outside", "somewhere",
    "aboard", "across", "around", "within", "beyond", "throughout", "among", "amid",
)

/**
 * The setting as a phrase that can follow a noun or open a sentence ("in Lisbon", "no Porto, nos anos 60"): kept as
 * typed when it already starts with a preposition, otherwise "em"/"in" is put in front.
 */
internal fun placePhrase(setting: String, language: BotLanguage): String {
    val first = setting.substringBefore(' ').trimEnd(',').lowercase()
    val rest = setting.substringAfter(" ", "")
    return when (language) {
        BotLanguage.PT -> when {
            first in PT_PREPOSITIONS -> setting.replaceFirstChar { it.lowercase() }
            first in PT_ARTICLES && rest.isNotEmpty() -> "${PT_ARTICLES.getValue(first)} $rest"
            else -> "em $setting"
        }
        BotLanguage.EN -> if (first in EN_PREPOSITIONS) setting.replaceFirstChar { it.lowercase() } else "in $setting"
    }
}

/**
 * A 120–180-word abstract for a book from [answers], in [language]. [variant] picks the wording (0, 1, 2, then again):
 * an opening for the genre, the trouble, the tone and a closing, with linking sentences added to reach 120 words.
 */
fun writeAbstract(answers: IdeaAnswers, language: BotLanguage, variant: Int): String {
    val v = ((variant % 3) + 3) % 3
    val t = if (language == BotLanguage.PT) PT else EN
    val character = cutAnswer(answers.character)
    val where = placePhrase(cutAnswer(answers.setting), language)
    val conflict = cutAnswer(answers.conflict)
    fun fill(s: String) = s.replace("{c}", character).replace("{W}", where.replaceFirstChar { it.uppercase() })
        .replace("{w}", where).replace("{x}", conflict)
        .replaceFirstChar { it.uppercase() }
    val body = listOf(
        fill(t.openings.getValue(answers.genre)[v]),
        fill(t.troubles[v]),
        t.tones.getValue(answers.tone)[v],
    )
    val closing = t.closings.getValue(answers.genre)
    val hooks = mutableListOf<String>()
    fun text() = (body + hooks + closing).joinToString(" ")
    var next = v
    while (words(text()) < MIN_WORDS && hooks.size < t.hooks.size) {
        hooks += t.hooks[next % t.hooks.size]
        next++
    }
    while (words(text()) > MAX_WORDS && hooks.isNotEmpty()) hooks.removeAt(hooks.lastIndex)
    return text()
}

private fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

private class Templates(
    val openings: Map<Genre, List<String>>,
    val troubles: List<String>,
    val tones: Map<Tone, List<String>>,
    val closings: Map<Genre, String>,
    val hooks: List<String>,
)

private val EN = Templates(
    openings = mapOf(
        Genre.ROMANCE to listOf(
            "Meet {c}. {W}, where every street seems to hold a memory, love is the last thing on their mind, and soon the one thing they cannot stop thinking about.",
            "{c} has a plan for everything, except the heart. Set {w}, this is the story of an encounter that turns a careful, ordered life into something wonderfully unpredictable.",
            "Some meetings change everything. For {c}, {w}, it begins with a glance, a misunderstanding and a conversation that neither of them will ever be able to forget.",
        ),
        Genre.MYSTERY to listOf(
            "Meet {c}. {W}, nothing is quite what it seems, and the quiet routine of ordinary days hides questions that nobody wants answered, least of all those who know the truth.",
            "{c} notices what others miss. Set {w}, this mystery begins with a small detail out of place and grows into a puzzle that touches everyone they trust.",
            "Every place has its secrets, and there are more of them {w} than anyone suspects. When {c} starts asking questions, the answers lead somewhere far darker than anyone expected.",
        ),
        Genre.FANTASY to listOf(
            "Meet {c}. {W}, magic is whispered about but never seen, until the day it chooses them and the world they thought they knew begins to unravel.",
            "{c} never asked to be special. Set {w}, where old stories turn out to be true, this tale follows an unlikely hero into a world of wonder and danger.",
            "Long ago, a promise was made {w}. Now {c} is the only one who can keep it, armed with courage, a little luck and powers they barely understand.",
        ),
        Genre.SCIENCE_FICTION to listOf(
            "Meet {c}. {W}, technology has answered almost every question humanity ever asked, except the one that has kept them awake at night for years.",
            "{c} lives by the rules of a carefully engineered world. Set {w}, this story asks what happens when one person finds the flaw in the perfect system.",
            "The future arrived quietly {w}. For {c}, it comes with a signal nobody else can hear and a choice that could change the course of everything.",
        ),
        Genre.HISTORICAL to listOf(
            "Meet {c}. {W}, history is being written in the streets and in the drawing rooms, and ordinary people are about to be caught in its current.",
            "{c} belongs to a world on the edge of change. Set {w}, this novel brings a vanished time to life through one person's courage and choices.",
            "The year turns, and nothing will ever be the same again {w}. Caught between duty and desire, {c} must decide which side of history to stand on.",
        ),
        Genre.THRILLER to listOf(
            "Meet {c}. {W}, a single phone call is all it takes to turn an ordinary life into a race against time, with no one left to trust.",
            "{c} thought the past was buried. Set {w}, this thriller follows them through a dangerous few days in which every step could be their last.",
            "The clock is already running {w}. For {c}, the only way out is forward, through lies, threats and a truth that someone will do anything to protect.",
        ),
        Genre.DRAMA to listOf(
            "Meet {c}. {W}, life follows its familiar rhythm of small joys and quiet disappointments, until one decision sets everything in motion.",
            "{c} has spent years keeping the peace. Set {w}, this is a story about family, loyalty and the price of the things we never say aloud.",
            "Some lives change in a single afternoon. {W}, {c} faces the moment that will test every bond they have and every belief they hold.",
        ),
        Genre.COMEDY to listOf(
            "Meet {c}. {W}, things rarely go to plan, and today they are going spectacularly, gloriously and hilariously wrong in every possible direction.",
            "{c} only wanted a quiet week. Set {w}, this comedy follows a chain of small mistakes that snowballs into the most ridiculous adventure of their life.",
            "Nobody has ever seen anything like it {w}. And at the centre of the chaos, trying very hard to look innocent, stands {c}.",
        ),
    ),
    troubles = listOf(
        "Then comes the trouble: {x}. From that moment on, there is no going back, and every choice carries a cost.",
        "Everything shifts when it becomes clear: {x}. What follows will test their courage, their patience and their sense of who they are.",
        "At the heart of it all lies one problem: {x}. Solving it will mean risking far more than they ever imagined.",
    ),
    tones = mapOf(
        Tone.LIGHT to listOf(
            "Warm, witty and full of small delights, this is a story to read with a smile and a cup of tea close at hand.",
            "Gentle and bright, it celebrates the little things that make life worth living, and the people who make them shine.",
            "Light on its feet and generous in spirit, it is the kind of book that leaves you feeling better about the world.",
        ),
        Tone.DARK to listOf(
            "Shadowy and unsettling, it explores the corners of the human heart that most people would rather keep hidden from view.",
            "Brooding and intense, it lingers long after the last page, asking uncomfortable questions about guilt, fear and choice.",
            "With a darkness that creeps in slowly, it shows how easily ordinary people can be pushed to the very edge.",
        ),
        Tone.MOVING to listOf(
            "Tender and deeply human, it is a story about loss, hope and the quiet strength it takes to keep going.",
            "Heartfelt and honest, it will make you laugh, make you cry and remind you why the people we love matter most.",
            "Quietly powerful, it follows its characters with compassion, finding beauty in the smallest moments of courage and kindness.",
        ),
        Tone.FUNNY to listOf(
            "Sharp, playful and full of laugh-out-loud moments, it proves that even the worst days can make the best stories.",
            "Packed with comic misunderstandings and unforgettable characters, it is pure, joyful entertainment from the first page to the last.",
            "Irreverent and charming, it pokes fun at life's absurdities while never losing sight of the warmth underneath.",
        ),
        Tone.SUSPENSEFUL to listOf(
            "Tense and gripping, it keeps the pages turning late into the night, with a twist waiting around every corner.",
            "Built on mounting tension and sudden reversals, it will keep you guessing until the very last chapter.",
            "Every chapter tightens the knot, and every answer raises a new question, until the final, breathless reveal.",
        ),
    ),
    closings = mapOf(
        Genre.ROMANCE to "A love story about second chances, brave hearts and the courage it takes to let someone in.",
        Genre.MYSTERY to "A mystery for readers who love clever clues, hidden motives and a solution they will never see coming.",
        Genre.FANTASY to "A fantasy adventure about destiny, friendship and the magic that hides in the most unexpected places.",
        Genre.SCIENCE_FICTION to "A science fiction story about progress, freedom and what it really means to be human.",
        Genre.HISTORICAL to "A historical novel about ordinary lives in extraordinary times, rich in detail and full of heart.",
        Genre.THRILLER to "A thriller about trust, survival and the lengths people will go to when everything is at stake.",
        Genre.DRAMA to "A drama about family, regret and the long road towards forgiveness and understanding.",
        Genre.COMEDY to "A comedy about friendship, failure and the joy of finding out that life rarely goes to plan.",
    ),
    hooks = listOf(
        "Along the way, friendships are forged, old secrets come to light and nothing turns out quite as planned.",
        "Each chapter brings them closer to the truth, and a little further from the life they once knew.",
        "By the end, they will have to decide what they are truly willing to lose, and what they will fight to keep.",
        "It is a journey of small victories and painful setbacks, told with an eye for the details that make a life.",
    ),
)

private val PT = Templates(
    openings = mapOf(
        Genre.ROMANCE to listOf(
            "Conheça {c}. {W}, onde cada rua parece guardar uma memória, o amor é a última coisa em que pensa, e em breve a única de que não se consegue esquecer.",
            "{c} tem um plano para tudo, menos para o coração. Passada {w}, esta é a história de um encontro que transforma uma vida arrumada em algo maravilhosamente imprevisível.",
            "Há encontros que mudam tudo. Para {c}, {w}, começa com um olhar, um mal-entendido e uma conversa que nenhum dos dois vai conseguir esquecer.",
        ),
        Genre.MYSTERY to listOf(
            "Conheça {c}. {W}, nada é bem o que parece, e a rotina tranquila dos dias comuns esconde perguntas a que ninguém quer responder, muito menos quem sabe a verdade.",
            "{c} repara no que os outros não veem. Passado {w}, este mistério começa com um pequeno pormenor fora do sítio e cresce até tocar todos aqueles em quem confia.",
            "Todos os lugares têm segredos, e {w} há mais do que se imagina. Quando {c} começa a fazer perguntas, as respostas levam a um sítio muito mais sombrio do que alguém imaginava.",
        ),
        Genre.FANTASY to listOf(
            "Conheça {c}. {W}, fala-se de magia em voz baixa mas nunca ninguém a viu, até ao dia em que ela escolhe esta personagem e o mundo que conhecia começa a desfazer-se.",
            "{c} nunca pediu para ser especial. Passada {w}, onde as velhas lendas afinal são verdadeiras, esta história segue um herói improvável num mundo de maravilhas e perigos.",
            "Há muito tempo, foi feita uma promessa {w}. Agora só {c} a pode cumprir, com coragem, alguma sorte e poderes que mal compreende.",
        ),
        Genre.SCIENCE_FICTION to listOf(
            "Conheça {c}. {W}, a tecnologia respondeu a quase todas as perguntas que a humanidade alguma vez fez, menos àquela que lhe tira o sono há anos.",
            "{c} vive segundo as regras de um mundo cuidadosamente planeado. Passada {w}, esta história pergunta o que acontece quando alguém encontra a falha no sistema perfeito.",
            "O futuro chegou sem alarde {w}. Para {c}, chega com um sinal que mais ninguém ouve e uma escolha que pode mudar o rumo de tudo.",
        ),
        Genre.HISTORICAL to listOf(
            "Conheça {c}. {W}, a história escreve-se nas ruas e nos salões, e as pessoas comuns estão prestes a ser arrastadas pela sua corrente.",
            "{c} pertence a um mundo à beira da mudança. Passado {w}, este romance dá vida a um tempo desaparecido através da coragem e das escolhas de uma só pessoa.",
            "O ano muda, e nada voltará a ser igual {w}. Entre o dever e o desejo, {c} tem de decidir de que lado da história quer ficar.",
        ),
        Genre.THRILLER to listOf(
            "Conheça {c}. {W}, basta um telefonema para transformar uma vida comum numa corrida contra o tempo, sem ninguém em quem confiar.",
            "{c} julgava que o passado estava enterrado. Passado {w}, este thriller acompanha dias perigosos em que cada passo pode ser o último.",
            "{W}, o relógio já está a contar. Para {c}, a única saída é seguir em frente, entre mentiras, ameaças e uma verdade que alguém está disposto a tudo para esconder.",
        ),
        Genre.DRAMA to listOf(
            "Conheça {c}. {W}, a vida segue o seu ritmo conhecido de pequenas alegrias e desilusões silenciosas, até que uma decisão põe tudo em movimento.",
            "{c} passou anos a manter a paz. Passado {w}, este é um livro sobre família, lealdade e o preço de tudo aquilo que nunca dizemos em voz alta.",
            "Há vidas que mudam numa só tarde. {W}, {c} enfrenta o momento que vai pôr à prova todos os laços que tem e tudo aquilo em que acredita.",
        ),
        Genre.COMEDY to listOf(
            "Conheça {c}. {W}, as coisas raramente correm como planeado, e hoje estão a correr espetacular, gloriosa e hilariantemente mal em todas as direções possíveis.",
            "{c} só queria uma semana tranquila. Passada {w}, esta comédia segue uma cadeia de pequenos erros que cresce até à aventura mais ridícula da sua vida.",
            "Nunca ninguém viu nada assim {w}. E no centro do caos, a esforçar-se muito por parecer inocente, está {c}.",
        ),
    ),
    troubles = listOf(
        "Até que surge o problema: {x}. A partir desse momento não há volta atrás, e cada escolha tem um preço.",
        "Tudo muda quando se torna claro: {x}. O que se segue vai pôr à prova a coragem, a paciência e a própria identidade.",
        "No centro de tudo há um só problema: {x}. Resolvê-lo vai obrigar a arriscar muito mais do que alguma vez imaginou.",
    ),
    tones = mapOf(
        Tone.LIGHT to listOf(
            "Caloroso, espirituoso e cheio de pequenos prazeres, é um livro para ler com um sorriso e uma chávena de chá ao lado.",
            "Suave e luminoso, celebra as pequenas coisas que dão sentido à vida e as pessoas que as fazem brilhar.",
            "Leve e generoso, é daqueles livros que nos deixam a gostar um pouco mais do mundo.",
        ),
        Tone.DARK to listOf(
            "Sombrio e inquietante, explora os cantos do coração humano que a maioria das pessoas prefere manter escondidos.",
            "Denso e intenso, fica connosco muito depois da última página, a levantar perguntas incómodas sobre culpa, medo e escolha.",
            "Com uma escuridão que se instala devagar, mostra como é fácil levar pessoas comuns até ao limite.",
        ),
        Tone.MOVING to listOf(
            "Terno e profundamente humano, é uma história sobre perda, esperança e a força silenciosa que é preciso para continuar.",
            "Sincero e comovente, vai fazer rir, fazer chorar e lembrar por que razão as pessoas de quem gostamos são o que mais importa.",
            "Discretamente poderoso, acompanha as suas personagens com compaixão e encontra beleza nos mais pequenos gestos de coragem e bondade.",
        ),
        Tone.FUNNY to listOf(
            "Afiado, brincalhão e cheio de momentos de rir às gargalhadas, prova que até os piores dias dão as melhores histórias.",
            "Repleto de mal-entendidos cómicos e personagens inesquecíveis, é puro divertimento da primeira à última página.",
            "Irreverente e encantador, goza com os absurdos da vida sem nunca perder o calor humano que está por baixo.",
        ),
        Tone.SUSPENSEFUL to listOf(
            "Tenso e viciante, obriga a virar páginas pela noite dentro, com uma reviravolta à espera em cada esquina.",
            "Construído sobre uma tensão crescente e reviravoltas súbitas, vai manter o leitor a adivinhar até ao último capítulo.",
            "Cada capítulo aperta o nó e cada resposta levanta uma nova pergunta, até à revelação final, de cortar a respiração.",
        ),
    ),
    closings = mapOf(
        Genre.ROMANCE to "Uma história de amor sobre segundas oportunidades, corações corajosos e a coragem de deixar alguém entrar.",
        Genre.MYSTERY to "Um mistério para quem adora pistas engenhosas, motivos escondidos e uma solução que nunca se adivinha.",
        Genre.FANTASY to "Uma aventura fantástica sobre destino, amizade e a magia que se esconde nos lugares mais inesperados.",
        Genre.SCIENCE_FICTION to "Uma história de ficção científica sobre progresso, liberdade e o que significa realmente ser humano.",
        Genre.HISTORICAL to "Um romance histórico sobre vidas comuns em tempos extraordinários, rico em pormenores e cheio de coração.",
        Genre.THRILLER to "Um thriller sobre confiança, sobrevivência e até onde se vai quando tudo está em jogo.",
        Genre.DRAMA to "Um drama sobre família, arrependimento e o longo caminho até ao perdão e à compreensão.",
        Genre.COMEDY to "Uma comédia sobre amizade, falhanço e a alegria de descobrir que a vida raramente corre como planeado.",
    ),
    hooks = listOf(
        "Pelo caminho nascem amizades, vêm à luz segredos antigos e nada acontece exatamente como estava previsto.",
        "Cada capítulo aproxima a personagem da verdade e afasta-a um pouco mais da vida que conhecia.",
        "No fim, terá de decidir o que está realmente disposta a perder, e aquilo por que vale a pena lutar.",
        "É uma viagem de pequenas vitórias e reveses dolorosos, contada com atenção aos pormenores que fazem uma vida.",
    ),
)
