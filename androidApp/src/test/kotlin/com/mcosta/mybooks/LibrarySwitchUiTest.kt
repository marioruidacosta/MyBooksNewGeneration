package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.platform.CoverCamera
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.platform.IsbnScanner
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.social.ActivityService
import com.mcosta.mybooks.social.AuthService
import com.mcosta.mybooks.social.FriendsService
import com.mcosta.mybooks.social.ProfileService
import com.mcosta.mybooks.social.SafetyService
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.SessionState
import com.mcosta.mybooks.ui.App
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/** Switching libraries rebuilds the whole app: nothing of the previous library stays on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySwitchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val run = "switch-${System.nanoTime()}"
    private val files = context.filesDir.toOkioPath() / run
    private fun dbName(id: LibraryId) = "$run-${id.key}.db"
    private val accountA = LibraryId.Account("a")
    private val accountB = LibraryId.Account("b")
    private val newReader = LibraryId.Account("new-reader")

    /** The community, as far as signing in goes: the session, and no profile yet (a new account). */
    private val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    private val auth = object : AuthService {
        override val session = this@LibrarySwitchUiTest.session
        override suspend fun sendCode(email: String) = Unit
        override suspend fun verifyCode(email: String, code: String) = Unit
        override suspend fun signOut() {
            session.value = SessionState.SignedOut
        }
    }

    /** [T] answering [answer] (by method name); a call it doesn't answer fails as if offline, and its flows are empty. */
    private inline fun <reified T> fake(crossinline answer: (String) -> Any? = { throw IllegalStateException("offline") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> T::class.java.simpleName
                }
                Flow::class.java.isAssignableFrom(method.returnType) -> emptyFlow<Any>()
                else -> answer(method.name)
            }
        } as T

    private val profiles = fake<ProfileService> { name -> if (name == "myProfile") null else throw IllegalStateException("offline") }

    private val social = SocialServices(auth, profiles, fake<FriendsService>(), fake<SafetyService>(), fake<ActivityService>())

    private fun container(social: SocialServices? = null) = AppContainer(
        openDatabase = { id -> createAndroidDatabase(context, dbName(id)) },
        deleteDatabase = { id -> context.deleteDatabase(dbName(id)) },
        libraryFiles = LibraryFiles(FileSystem.SYSTEM, files),
        httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
        googleBooksApiKey = "",
        userAgent = "MyBooks test",
        releasesFileSystem = FileSystem.SYSTEM,
        releasesDir = files / "releases",
        socialServices = social,
    ).also { runBlocking { it.start() } }

    private val services = PlatformServices(
        scanner = object : IsbnScanner { override suspend fun scan() = ScanOutcome.Cancelled },
        camera = object : CoverCamera { override suspend fun takePhoto(): ByteArray? = null },
        shrinker = object : PhotoShrinker { override fun shrink(photo: ByteArray, maxSide: Int) = photo },
        documents = object : DocumentPicker {
            override suspend fun save(suggestedName: String, mime: String, bytes: ByteArray) = false
            override suspend fun open(mimeTypes: List<String>): ByteArray? = null
        },
    )

    @After
    fun tearDown() {
        for (id in listOf(LibraryId.Local, accountA, accountB, newReader)) context.deleteDatabase(dbName(id))
        FileSystem.SYSTEM.deleteRecursively(files)
    }

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun switchShowsNoStaleBooks() {
        val container = container()
        fun library(id: LibraryId, vararg titles: String) = runBlocking {
            container.libraries.activate(id)
            for (title in titles) container.repository.save(Book(title = title, authors = "Eça de Queirós", status = ReadingStatus.READING))
        }
        library(accountA, "A Cidade e as Serras", "O Mandarim")
        library(accountB, "Os Maias", "A Relíquia")
        compose.setContent { App(container, services) }
        waitFor { shown("Os Maias") }

        // A screen is still showing B: B stays open until it lets go.
        library(accountA)
        waitFor { shown("A Cidade e as Serras") }

        assertTrue(shown("O Mandarim"))
        for (title in listOf("Os Maias", "A Relíquia")) {
            assertTrue("$title still on screen", compose.onAllNodesWithText(title, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun signingInKeepsTheSignInFlowOpen() {
        val container = container(social)
        // Opened from Settings → "Join the MyBooks community".
        var route by mutableStateOf<String?>("account")
        compose.setContent { App(container, services, openRoute = route, onOpened = { route = null }) }
        waitFor { shown("Email me a code") }

        // The code is accepted: the session is the new account's, and its own (empty) library is opened.
        session.value = SessionState.SignedIn(newReader.userId, null)
        waitFor { container.libraries.active.value.id == newReader }

        // The rebuilt app goes on with signing in: a new account chooses its username.
        waitFor { shown("Choose how other readers will see you.") }
    }
}
