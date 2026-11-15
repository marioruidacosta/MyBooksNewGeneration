package com.mcosta.mybooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.library.deleteLibraryDatabase
import com.mcosta.mybooks.library.libraryDatabaseFile
import com.mcosta.mybooks.library.openDatabaseFile
import com.mcosta.mybooks.library.openLibraryDatabase
import com.mcosta.mybooks.social.ActivityService
import com.mcosta.mybooks.social.AuthService
import com.mcosta.mybooks.social.FriendsService
import com.mcosta.mybooks.social.ProfileService
import com.mcosta.mybooks.social.SafetyService
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.social.model.SessionState
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Sink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/** The app's own startup ([androidAppContainer]) upgrades today's library before anything opens it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fs = FileSystem.SYSTEM
    private val filesDir = context.filesDir.toOkioPath()
    private val appSettings = context.getSharedPreferences("mybooks", Context.MODE_PRIVATE)
    private val communitySettings = context.getSharedPreferences("mybooks_community", Context.MODE_PRIVATE)
    private var container: AppContainer? = null

    @After
    fun tearDown() {
        container?.let { c ->
            runBlocking { withTimeout(30.seconds) { c.libraries.active.value.close() } }
            c.applicationScope.cancel()
        }
        for (id in listOf(LibraryId.Local, LibraryId.Account("acc-a"), LibraryId.Account("acc-b"))) {
            val db = libraryDatabaseFile(context, id)
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(db.path + suffix).delete()
        }
        fs.deleteRecursively(libraryDatabaseFile(context, LibraryId.Account("acc-a")).parentFile!!.toOkioPath())
        for (dir in listOf("covers", "ebooks", "libraries")) fs.deleteRecursively(filesDir / dir)
        appSettings.edit().clear().commit()
        communitySettings.edit().clear().commit()
    }

    @Test
    fun upgradeRunsBeforeTheLibraryOpens() = runBlocking {
        withTimeout(60.seconds) {
            // Today's phone: one library, merged into two accounts (acc-b last), acc-a's session stored.
            val phone = openLibraryDatabase(context, LibraryId.Local)
            phone.bookDao().insert(Book(title = "Dune").toEntity())
            phone.bookDao().insert(Book(title = "Emma").toEntity())
            phone.syncStateDao().set("merged:acc-a", "1")
            phone.syncStateDao().set("merged:acc-b", "1")
            phone.close()
            fs.createDirectories(filesDir / "covers")
            fs.write(filesDir / "covers" / "c1.jpg") { writeUtf8("jpeg") }
            appSettings.edit().putString("home.pinnedTags", "Poetry").commit()
            communitySettings.edit()
                .putString("session", """{"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"bearer","user":{"id":"acc-a"}}""")
                .commit()

            val c = androidAppContainer(context, googleBooksApiKey = "", appVersion = "test", socialConfig = SocialConfig("", ""))
            container = c

            // It starts on the signed-in owner's library, holding the books, covers and pinned tags.
            assertEquals(LibraryId.Account("acc-a"), c.libraries.active.value.id)
            assertEquals("acc-a", appSettings.getString("library.active", null))
            assertEquals(listOf("Dune", "Emma"), c.repository.observeBooks(null, "").first().map { it.title }.sorted())
            c.pinnedTags.tags.first { it == listOf("Poetry") }
            assertEquals(true, c.coverStore.exists("cover:c1.jpg"))
            assertFalse(fs.exists(filesDir / "covers" / "c1.jpg"))
        }
    }

    /** The session, as far as switching libraries goes; every other community call fails as if offline. */
    private val session = MutableStateFlow<SessionState>(SessionState.Loading)

    private inline fun <reified T> offline(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> T::class.java.simpleName
                }
                Flow::class.java.isAssignableFrom(method.returnType) -> emptyFlow<Any>()
                else -> throw IllegalStateException("offline")
            }
        } as T

    private val social = SocialServices(
        object : AuthService {
            override val session = this@AppContainerUpgradeTest.session
            override suspend fun sendCode(email: String) = Unit
            override suspend fun verifyCode(email: String, code: String) = Unit
            override suspend fun signOut() {
                session.value = SessionState.SignedOut
            }
        },
        offline<ProfileService>(), offline<FriendsService>(), offline<SafetyService>(), offline<ActivityService>(),
    )

    /** The phone's disk: full ([full]) for the library copies, and every delete of an account's database noted. */
    private inner class Disk : ForwardingFileSystem(FileSystem.SYSTEM) {
        @Volatile
        var full = false
        val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val accountDbs = libraryDatabaseFile(context, LibraryId.Account("acc-a")).parentFile!!.toOkioPath()

        override fun sink(file: Path, mustCreate: Boolean): Sink {
            if (full && file.parent == accountDbs) throw IOException("No space left on device")
            return super.sink(file, mustCreate)
        }

        override fun delete(path: Path, mustExist: Boolean) {
            val database = path.name.substringBefore("-") == "acc-a.db"
            if (path.parent == accountDbs && database && fs.exists(path)) deleted += path.name
            super.delete(path, mustExist)
        }
    }

    /** A start of the app, as [androidAppContainer] makes it, on [disk] and with the community's session. */
    private suspend fun start(disk: Disk) = AppContainer(
        openDatabase = { id -> openLibraryDatabase(context, id) },
        deleteDatabase = { id ->
            disk.deleted += "library ${id.key}"
            deleteLibraryDatabase(context, id)
        },
        libraryFiles = LibraryFiles(disk, filesDir),
        databaseFile = { id -> libraryDatabaseFile(context, id).toOkioPath() },
        openDatabaseFile = { path -> openDatabaseFile(context, path.toFile()) },
        httpEngine = MockEngine { respondError(HttpStatusCode.NotFound) },
        googleBooksApiKey = "",
        userAgent = "MyBooks test",
        socialServices = social,
        appSettings = SharedPreferencesSettings(appSettings),
        releasesFileSystem = FileSystem.SYSTEM,
        releasesDir = filesDir / "releases",
    ).also {
        container = it
        it.start()
    }

    /** The app is closed: its work stopped and its library closed. */
    private suspend fun stop(c: AppContainer) {
        c.applicationScope.coroutineContext[Job]!!.cancelAndJoin()
        c.libraries.active.value.close()
        container = null
    }

    @Test
    fun failedUpgradeThenOwnerSignsInNeverOffersOrDeletes() = runBlocking {
        withTimeout(90.seconds) {
            // Today's phone: one library with two books, merged into acc-a's cloud library.
            val phone = openLibraryDatabase(context, LibraryId.Local)
            phone.bookDao().insert(Book(title = "Dune").toEntity())
            phone.bookDao().insert(Book(title = "Emma").toEntity())
            phone.syncStateDao().set("merged:acc-a", "1")
            phone.close()
            val owner = LibraryId.Account("acc-a")
            val ownerDb = libraryDatabaseFile(context, owner)

            // The first start of this version: the disk is full, so the upgrade fails before its copy is checked.
            val disk = Disk().apply { full = true }
            val first = start(disk)
            assertEquals(LibraryId.Local, first.libraries.active.value.id)

            // The owner's session is restored.
            session.value = SessionState.SignedIn(owner.userId, null)
            combine(first.libraries.active, first.libraries.openFailed, first.libraries.offer) { active, failed, offer ->
                active.id == owner || failed != null || offer != null
            }.first { it }

            // Nothing is made for the owner: the phone's library stays, with "Couldn't open your library".
            assertEquals(LibraryId.Local, first.libraries.active.value.id)
            assertEquals(owner, first.libraries.openFailed.value)
            assertNull(first.libraries.offer.value)
            assertFalse(ownerDb.exists())
            assertEquals(listOf("Dune", "Emma"), first.repository.observeBooks(null, "").first().map { it.title }.sorted())
            stop(first)

            // The next start, with room on the disk: the upgrade is tried again and the books are the owner's.
            disk.full = false
            val next = start(disk)
            assertEquals(owner, next.libraries.active.value.id)
            assertEquals(listOf("Dune", "Emma"), next.repository.observeBooks(null, "").first().map { it.title }.sorted())
            next.libraries.openFailed.first { it == null }
            assertNull(next.libraries.offer.value)

            // No account's database was ever deleted.
            assertEquals(emptyList<String>(), disk.deleted.toList())
        }
    }
}
