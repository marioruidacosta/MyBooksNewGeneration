package com.mcosta.mybooks.ui.booknerd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.suggestions.Suggestion
import com.mcosta.mybooks.data.suggestions.Suggestions
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.social.model.SuggestedReader
import com.mcosta.mybooks.ui.social.LibraryShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One entry of the BookNerd conversation. */
sealed interface BotMessage {
    val id: Long

    data class BotText(override val id: Long, val text: String) : BotMessage
    data class UserText(override val id: Long, val text: String) : BotMessage
    data class BookCards(override val id: Long, val books: List<Suggestion>) : BotMessage
    data class PersonCards(override val id: Long, val readers: List<SuggestedReader>, val language: BotLanguage) : BotMessage
    data class AbstractCard(override val id: Long, val text: String) : BotMessage
    /** "Join the community to get friend suggestions", with Join. */
    data class JoinPrompt(override val id: Long) : BotMessage
}

data class BookNerdState(
    val messages: List<BotMessage> = emptyList(),
    val chips: List<String> = emptyList(),
    /** People a friend request was sent to (their "Add friend" shows "Request sent"). */
    val requested: Set<String> = emptySet(),
    /** Suggested books added to the library, by suggestion key, with the shelf. */
    val added: Map<String, ReadingStatus> = emptyMap(),
    val busy: Boolean = false,
)

const val CHIP_BOOKS = "Suggest books"
const val CHIP_FRIENDS = "Suggest friends"
const val CHIP_ABSTRACT = "Write an abstract"
const val CHIP_MORE = "More"
const val CHIP_ELSE = "Something else"
const val CHIP_RETRY = "Retry"
const val CHIP_ANOTHER = "Another version"
const val CHIP_START_OVER = "Start over"
private val MAIN_CHIPS = listOf(CHIP_BOOKS, CHIP_FRIENDS, CHIP_ABSTRACT)
private val GENRE_CHIPS = listOf("Romance", "Mystery", "Fantasy", "Science fiction", "Historical", "Thriller", "Drama", "Comedy")
private val TONE_CHIPS = listOf("Light", "Dark", "Moving", "Funny", "Suspenseful")

/** Books shown at a time. */
const val BOOKS_PAGE = 5

private const val GREETING = "Olá! I'm BookNerd. I can suggest books, find you fellow readers, or write an abstract for a book idea."

/**
 * BookNerd: a chat that suggests books ([suggestions], the app's engine), suggests readers to befriend ([social]) and
 * writes an abstract from five answers. Typed text is understood by keywords ([understand]); during the abstract's
 * questions it is always an answer.
 */
class BookNerdViewModel(
    private val suggestions: suspend () -> Suggestions,
    private val social: SocialServices?,
    repository: BookRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BookNerdState())
    val state: StateFlow<BookNerdState> = _state.asStateFlow()

    private val shelf = LibraryShelf(repository)
    private var nextId = 0L
    private var language = BotLanguage.EN
    /** Bumped by every user message: a load started under an older value finished too late and must not show. */
    private var generation = 0L
    private var loads = 0

    private sealed interface Flow {
        data object Idle : Flow
        data class Books(val shown: Int) : Flow
        data object Friends : Flow
        data class Asking(val step: Int, val answers: List<String>) : Flow
        data class Written(val answers: IdeaAnswers, val variant: Int) : Flow
    }

    private var flow: Flow = Flow.Idle
    private var books: List<Suggestion>? = null

    init {
        say(GREETING)
        chips(MAIN_CHIPS)
    }

    /** A typed message. */
    fun send(text: String) {
        user(text)
        val asking = flow as? Flow.Asking
        if (asking != null) return answer(asking, text, typed = true)
        val understood = understand(text)
        language = understood.language
        when (understood.intent) {
            BotIntent.BOOKS -> startBooks()
            BotIntent.FRIENDS -> startFriends()
            BotIntent.ABSTRACT -> startAbstract()
            null -> if (understood.options.isNotEmpty()) {
                say(pick("Do you mean…?", "Queres dizer…?"))
                chips(understood.options.map(::chipFor))
            } else {
                say(pick("I can help with three things:", "Posso ajudar com três coisas:"))
                chips(MAIN_CHIPS)
            }
        }
    }

    /** A tapped chip (shown as the user's message). */
    fun chip(label: String) {
        if (_state.value.busy && label == CHIP_MORE) return
        user(label)
        val asking = flow as? Flow.Asking
        when {
            label == CHIP_START_OVER -> {
                flow = Flow.Idle
                say(pick("What would you like to do?", "O que queres fazer?"))
                chips(MAIN_CHIPS)
            }
            asking != null -> answer(asking, label, typed = false)
            label == CHIP_BOOKS -> startBooks()
            label == CHIP_FRIENDS || label == CHIP_RETRY -> startFriends()
            label == CHIP_ABSTRACT -> startAbstract()
            label == CHIP_MORE -> moreBooks()
            label == CHIP_ELSE -> {
                flow = Flow.Idle
                say(pick("I can help with three things:", "Posso ajudar com três coisas:"))
                chips(MAIN_CHIPS)
            }
            label == CHIP_ANOTHER -> anotherVersion()
            else -> send(label)
        }
    }

    // Books ------------------------------------------------------------------------------------------------------

    private fun startBooks() {
        flow = Flow.Books(0)
        val known = books
        if (known != null) return showBooks()
        val mine = generation
        loading(true)
        viewModelScope.launch {
            try {
                books = suggestions().mix(Int.MAX_VALUE)
                if (mine != generation) return@launch
                showBooks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine != generation) return@launch
                flow = Flow.Idle
                say(pick("I couldn't load suggestions right now.", "Não consegui carregar sugestões agora."))
                chips(MAIN_CHIPS)
            } finally {
                loading(false)
            }
        }
    }

    private fun showBooks() {
        val all = books.orEmpty()
        val shown = (flow as? Flow.Books)?.shown ?: 0
        if (all.isEmpty()) {
            flow = Flow.Idle
            say(pick("Add a few books you loved and I'll find more like them.", "Junta alguns livros de que gostaste e eu encontro mais parecidos."))
            return chips(MAIN_CHIPS)
        }
        val page = all.drop(shown).take(BOOKS_PAGE)
        if (page.isEmpty()) {
            say(pick("That's all I have for now.", "Por agora é tudo."))
            return chips(listOf(CHIP_ELSE))
        }
        if (shown == 0) say(pick("Here are some books you might like:", "Aqui vão alguns livros que podes gostar:"))
        add(BotMessage.BookCards(id(), page))
        flow = Flow.Books(shown + page.size)
        chips(listOf(CHIP_MORE, CHIP_ELSE))
    }

    private fun moreBooks() {
        if (flow !is Flow.Books || books == null) return
        showBooks()
    }

    /** "To read" / "To buy" on a book card: added once, with that shelf. */
    fun addBook(s: Suggestion, status: ReadingStatus) {
        if (s.key in _state.value.added) return
        _state.update { it.copy(added = it.added + (s.key to status)) }
        viewModelScope.launch {
            shelf.add(Book(title = s.title, authors = s.authors, isbn = s.catalog?.isbn, coverUrl = s.coverUrl, status = status))
        }
    }

    // Friends ----------------------------------------------------------------------------------------------------

    private fun startFriends() {
        flow = Flow.Friends
        val s = social
        if (s == null || s.auth.session.value !is SessionState.SignedIn) {
            add(BotMessage.JoinPrompt(id()))
            return chips(MAIN_CHIPS)
        }
        val mine = generation
        loading(true)
        viewModelScope.launch {
            try {
                val readers = s.friends.suggestedReaders()
                if (mine != generation) return@launch
                if (readers.isEmpty()) {
                    say(pick("I couldn't find anyone yet — invite your friends!", "Ainda não encontrei ninguém — convida os teus amigos!"))
                } else {
                    add(BotMessage.PersonCards(id(), readers, language))
                }
                chips(listOf(CHIP_ELSE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine != generation) return@launch
                say(pick("Couldn't reach the community.", "Não consegui chegar à comunidade."))
                chips(listOf(CHIP_RETRY, CHIP_ELSE))
            } finally {
                loading(false)
            }
        }
    }

    /** "Add friend" on a person card: one request, however many taps. */
    fun addFriend(r: SuggestedReader) {
        val s = social ?: return
        val id = r.person.id
        if (id in _state.value.requested) return
        _state.update { it.copy(requested = it.requested + id) }
        viewModelScope.launch {
            try {
                s.friends.sendRequest(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(requested = it.requested - id) }
                say(pick("Couldn't send the request.", "Não consegui enviar o pedido."))
            }
        }
    }

    // Abstracts --------------------------------------------------------------------------------------------------

    private fun startAbstract() {
        flow = Flow.Asking(0, emptyList())
        ask(0)
    }

    private fun ask(step: Int) {
        say(QUESTIONS[step].let { (en, pt) -> pick(en, pt) })
        chips(
            when (step) {
                0 -> GENRE_CHIPS + CHIP_START_OVER
                4 -> TONE_CHIPS + CHIP_START_OVER
                else -> listOf(CHIP_START_OVER)
            },
        )
    }

    private fun answer(asking: Flow.Asking, text: String, typed: Boolean) {
        if (typed && soundsPortuguese(text)) language = BotLanguage.PT
        if (text.isBlank()) {
            say(pick("Tell me a little more?", "Conta-me um pouco mais?"))
            return ask(asking.step)
        }
        val answers = asking.answers + text.trim()
        if (answers.size < QUESTIONS.size) {
            flow = Flow.Asking(answers.size, answers)
            return ask(answers.size)
        }
        val idea = IdeaAnswers(genreOf(answers[0]), answers[1], answers[2], answers[3], toneOf(answers[4]))
        write(idea, 0)
    }

    private fun write(idea: IdeaAnswers, variant: Int) {
        flow = Flow.Written(idea, variant)
        add(BotMessage.AbstractCard(id(), writeAbstract(idea, language, variant)))
        chips(listOf(CHIP_ANOTHER, CHIP_START_OVER))
    }

    private fun anotherVersion() {
        val written = flow as? Flow.Written ?: return
        write(written.answers, written.variant + 1)
    }

    // Conversation -----------------------------------------------------------------------------------------------

    private fun pick(en: String, pt: String) = if (language == BotLanguage.PT) pt else en
    private fun chipFor(intent: BotIntent) = when (intent) {
        BotIntent.BOOKS -> CHIP_BOOKS
        BotIntent.FRIENDS -> CHIP_FRIENDS
        BotIntent.ABSTRACT -> CHIP_ABSTRACT
    }
    private fun id() = nextId++
    private fun add(message: BotMessage) = _state.update { it.copy(messages = it.messages + message) }
    private fun say(text: String) = add(BotMessage.BotText(id(), text))
    private fun user(text: String) {
        generation++
        add(BotMessage.UserText(id(), text))
    }

    private fun loading(on: Boolean) {
        loads += if (on) 1 else -1
        _state.update { it.copy(busy = loads > 0) }
    }
    private fun chips(labels: List<String>) = _state.update { it.copy(chips = labels) }

    private companion object {
        val QUESTIONS = listOf(
            "What genre is it?" to "De que género é?",
            "Who is the main character? (a name and a few words)" to "Quem é a personagem principal? (um nome e algumas palavras)",
            "Where and when does it happen?" to "Onde e quando acontece?",
            "What goes wrong, or what do they want?" to "O que corre mal, ou o que quer a personagem?",
            "What tone?" to "Que tom?",
        )
    }
}
