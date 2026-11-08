package com.mcosta.mybooks.ui.booknerd

import com.mcosta.mybooks.MainDispatcherTest
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.suggestions.Suggestion
import com.mcosta.mybooks.data.suggestions.SuggestionSource
import com.mcosta.mybooks.data.suggestions.Suggestions
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.social.model.Person
import com.mcosta.mybooks.social.model.SuggestedReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BookNerdViewModelTest : MainDispatcherTest() {
    private val repo = BookRepository(FakeBookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String) = LookupResult.NotFound
    })
    private val fake = FakeSocial()

    private fun book(i: Int) = Suggestion(SuggestionSource.AUTHORS, "k$i", "Book $i", "Author $i", null, "Because you loved Saramago")
    private var books: suspend () -> Suggestions = { Suggestions(listOf(SuggestionSource.AUTHORS to (1..12).map { book(it) })) }

    private fun vm(social: Boolean = true) = BookNerdViewModel({ books() }, if (social) fake.services else null, repo)

    private fun BookNerdViewModel.texts() = state.value.messages.filterIsInstance<BotMessage.BotText>().map { it.text }
    private fun BookNerdViewModel.shownBooks() = state.value.messages.filterIsInstance<BotMessage.BookCards>().flatMap { it.books }
    private fun BookNerdViewModel.lastText() = texts().last()

    @Test
    fun greetsWithThreeChips() = runTest {
        val vm = vm()
        assertEquals("Olá! I'm BookNerd. I can suggest books, find you fellow readers, or write an abstract for a book idea.", vm.texts().single())
        assertEquals(listOf("Suggest books", "Suggest friends", "Write an abstract"), vm.state.value.chips)
    }

    @Test
    fun unknownTextGetsTheChips() = runTest {
        val vm = vm()
        vm.send("hello there")
        assertEquals("hello there", (vm.state.value.messages.filterIsInstance<BotMessage.UserText>().single()).text)
        assertEquals("I can help with three things:", vm.lastText())
        assertEquals(listOf("Suggest books", "Suggest friends", "Write an abstract"), vm.state.value.chips)
    }

    @Test
    fun booksFiveThenMoreThenEnd() = runTest {
        val vm = vm()
        vm.send("suggest a book")
        assertEquals("Here are some books you might like:", vm.lastText())
        assertEquals(5, vm.shownBooks().size)
        assertEquals(listOf("More", "Something else"), vm.state.value.chips)
        vm.chip("More")
        assertEquals(10, vm.shownBooks().size)
        vm.chip("More")
        assertEquals(12, vm.shownBooks().size)
        vm.chip("More")
        assertEquals("That's all I have for now.", vm.lastText())
        assertEquals(12, vm.shownBooks().size)
    }

    @Test
    fun booksInPortuguese() = runTest {
        val vm = vm()
        vm.send("sugere-me um livro")
        assertEquals("Aqui vão alguns livros que podes gostar:", vm.lastText())
    }

    @Test
    fun emptyLibraryHint() = runTest {
        books = { Suggestions(emptyList()) }
        val vm = vm()
        vm.chip("Suggest books")
        assertEquals("Add a few books you loved and I'll find more like them.", vm.lastText())
    }

    /** Review focus: the engine failing gives a friendly line, not a crash. */
    @Test
    fun bookEngineFailing() = runTest {
        books = { throw IllegalStateException("offline") }
        val vm = vm()
        vm.chip("Suggest books")
        assertEquals("I couldn't load suggestions right now.", vm.lastText())
    }

    /** Review focus: "More" tapped twice while books load shows each book once. */
    @Test
    fun moreTwiceNoDuplicates() = runTest {
        val gate = CompletableDeferred<Unit>()
        val all = (1..12).map { book(it) }
        books = { gate.await(); Suggestions(listOf(SuggestionSource.AUTHORS to all)) }
        val vm = vm()
        vm.chip("Suggest books")
        vm.chip("More")
        vm.chip("More")
        gate.complete(Unit)
        vm.chip("More")
        val shown = vm.shownBooks().map { it.key }
        assertEquals(shown.distinct(), shown)
        assertEquals(10, shown.size)
    }

    @Test
    fun addBookAddsOnceWithStatus() = runTest {
        val vm = vm()
        vm.chip("Suggest books")
        val first = vm.shownBooks().first()
        vm.addBook(first, ReadingStatus.WISHLIST)
        vm.addBook(first, ReadingStatus.TO_READ)
        assertEquals(ReadingStatus.WISHLIST, vm.state.value.added[first.key])
        val library = repo.observeBooks(null, "").first()
        assertEquals(listOf("Book 1" to ReadingStatus.WISHLIST), library.map { it.title to it.status })
    }

    @Test
    fun friendsSignedOutJoin() = runTest {
        val vm = vm()
        vm.chip("Suggest friends")
        assertIs<BotMessage.JoinPrompt>(vm.state.value.messages.last())
        val noSocial = vm(social = false)
        noSocial.chip("Suggest friends")
        assertIs<BotMessage.JoinPrompt>(noSocial.state.value.messages.last())
    }

    private fun reader(name: String) = SuggestedReader(Person("id-$name", name, name.replaceFirstChar { it.uppercase() }, null), 2, 0, emptyList(), "Os Maias")

    @Test
    fun friendsListed() = runTest {
        fake.signInAs(fake.addUser("ana"))
        fake.suggested = listOf(reader("carla"), reader("dora"))
        val vm = vm()
        vm.send("find me readers")
        val cards = assertIs<BotMessage.PersonCards>(vm.state.value.messages.last())
        assertEquals(listOf("carla", "dora"), cards.readers.map { it.person.username })
        assertEquals(BotLanguage.EN, cards.language)
    }

    @Test
    fun friendsEmpty() = runTest {
        fake.signInAs(fake.addUser("ana"))
        val vm = vm()
        vm.send("quero novos amigos")
        assertEquals("Ainda não encontrei ninguém — convida os teus amigos!", vm.lastText())
    }

    @Test
    fun friendsFailingThenRetry() = runTest {
        fake.signInAs(fake.addUser("ana"))
        fake.failSuggested = true
        val vm = vm()
        vm.chip("Suggest friends")
        assertEquals("Couldn't reach the community.", vm.lastText())
        assertEquals(listOf("Retry", "Something else"), vm.state.value.chips)
        fake.failSuggested = false
        fake.suggested = listOf(reader("carla"))
        vm.chip("Retry")
        assertIs<BotMessage.PersonCards>(vm.state.value.messages.last())
    }

    /** Review focus: two taps on "Add friend" send one request. */
    @Test
    fun addFriendOnce() = runTest {
        val ana = fake.addUser("ana")
        val carla = fake.addUser("carla")
        fake.signInAs(ana)
        val r = SuggestedReader(Person(carla, "carla", "Carla", null), 2, 0, emptyList(), "Os Maias")
        val vm = vm()
        vm.addFriend(r)
        vm.addFriend(r)
        assertEquals(setOf(carla), vm.state.value.requested)
        assertEquals(1, fake.services.friends.outgoingRequests().size)
    }

    private fun BookNerdViewModel.answerAll(vararg answers: String) = answers.forEach { send(it) }

    @Test
    fun abstractAsksFiveThenWrites() = runTest {
        val vm = vm()
        vm.send("tenho uma ideia para um livro")
        assertEquals("De que género é?", vm.lastText())
        assertTrue("Romance" in vm.state.value.chips)
        vm.chip("Mystery")
        assertEquals("Quem é a personagem principal? (um nome e algumas palavras)", vm.lastText())
        vm.answerAll("Teodoro, um farmacêutico tímido", "Lisboa, em 1910", "descobre uma carta")
        assertEquals("Que tom?", vm.lastText())
        vm.chip("Dark")
        val card = assertIs<BotMessage.AbstractCard>(vm.state.value.messages.last())
        assertTrue("Teodoro, um farmacêutico tímido" in card.text)
        assertTrue(card.text.startsWith("Conheça") || "Teodoro" in card.text)
        assertEquals(listOf("Another version", "Start over"), vm.state.value.chips)
    }

    @Test
    fun emptyAnswerAsksAgain() = runTest {
        val vm = vm()
        vm.chip("Write an abstract")
        vm.chip("Comedy")
        vm.send("   ")
        assertEquals("Who is the main character? (a name and a few words)", vm.lastText())
        assertEquals("Tell me a little more?", vm.texts()[vm.texts().size - 2])
    }

    /** Review focus: an answer that contains a keyword is an answer, not a new request. */
    @Test
    fun abstractAnswersAreNotIntents() = runTest {
        val vm = vm()
        vm.chip("Write an abstract")
        vm.chip("Drama")
        vm.send("a girl who loves books and her friends")
        assertEquals("Where and when does it happen?", vm.lastText())
    }

    @Test
    fun anotherVersionDiffers() = runTest {
        val vm = vm()
        vm.chip("Write an abstract")
        vm.chip("Fantasy")
        vm.answerAll("Ana, a baker", "Porto, today", "her oven starts talking")
        vm.chip("Funny")
        vm.chip("Another version")
        val cards = vm.state.value.messages.filterIsInstance<BotMessage.AbstractCard>()
        assertEquals(2, cards.size)
        assertTrue(cards[0].text != cards[1].text)
    }

    /** Final review, Important 1: books that arrive after the user moved on don't take the conversation back. */
    @Test
    fun lateBooksDoNotTakeOverTheAbstract() = runTest {
        val gate = CompletableDeferred<Unit>()
        books = { gate.await(); Suggestions(listOf(SuggestionSource.AUTHORS to (1..12).map { book(it) })) }
        val vm = vm()
        vm.chip("Suggest books")
        vm.send("write a blurb")
        gate.complete(Unit)
        assertEquals("What genre is it?", vm.lastText())
        assertTrue("Romance" in vm.state.value.chips)
        assertTrue(vm.shownBooks().isEmpty())
        assertEquals(false, vm.state.value.busy)
        vm.chip("Mystery")
        assertEquals("Who is the main character? (a name and a few words)", vm.lastText())
    }

    /** Final review, Important 1: a late friends answer neither shows nor turns its chips into abstract answers. */
    @Test
    fun lateFriendsDoNotTakeOverTheAbstract() = runTest {
        fake.signInAs(fake.addUser("ana"))
        fake.suggested = listOf(reader("carla"))
        val gate = CompletableDeferred<Unit>()
        fake.suggestedGate = gate
        val vm = vm()
        vm.chip("Suggest friends")
        vm.send("write a blurb")
        gate.complete(Unit)
        assertTrue(vm.state.value.messages.none { it is BotMessage.PersonCards })
        assertTrue("Romance" in vm.state.value.chips)
        assertEquals(false, vm.state.value.busy)
    }

    /** Final review, Important 3: a chip-started abstract answered in Portuguese is written in Portuguese. */
    @Test
    fun abstractFollowsTheLanguageOfTheAnswers() = runTest {
        val vm = vm()
        vm.chip("Write an abstract")
        vm.chip("Drama")
        vm.send("Teodoro, um farmacêutico tímido")
        assertEquals("Onde e quando acontece?", vm.lastText())
        vm.answerAll("Lisboa, em 1910", "descobre uma carta")
        vm.chip("Dark")
        val card = assertIs<BotMessage.AbstractCard>(vm.state.value.messages.last())
        assertTrue("Conheça" in card.text || "Passado" in card.text || "Há vidas" in card.text, card.text)
    }

    /** Final review, Important 4: a message that could mean two things asks which. */
    @Test
    fun ambiguousRequestAsksWhichOne() = runTest {
        val vm = vm()
        vm.send("a book for my friend's birthday")
        assertEquals("Do you mean…?", vm.lastText())
        assertEquals(listOf("Suggest books", "Suggest friends"), vm.state.value.chips)
    }

    @Test
    fun startOverReturnsToChips() = runTest {
        val vm = vm()
        vm.chip("Write an abstract")
        vm.chip("Start over")
        assertEquals(listOf("Suggest books", "Suggest friends", "Write an abstract"), vm.state.value.chips)
        vm.send("suggest a book")
        assertEquals(5, vm.shownBooks().size)
    }
}
