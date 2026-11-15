package com.mcosta.mybooks.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.Preferences
import com.mcosta.mybooks.data.ReadingEvents
import com.mcosta.mybooks.data.authors.GoogleBooksAuthorSearch
import com.mcosta.mybooks.data.db.AppDatabase
import com.mcosta.mybooks.data.db.createDatabase
import com.mcosta.mybooks.data.lookup.LookupChain
import com.mcosta.mybooks.data.lookup.lookupHttpClient
import com.mcosta.mybooks.data.releases.ReleasesClient
import com.mcosta.mybooks.data.releases.ReleasesRepository
import com.mcosta.mybooks.data.suggestions.SubjectSearch
import com.mcosta.mybooks.data.SaveResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.social.FakeSocial
import com.mcosta.mybooks.ui.library.LibraryViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mcosta.mybooks.social.SocialServices
import com.mcosta.mybooks.sync.SyncTable
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySpaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryId.Account("space-test")
    private val files = LibraryFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "space-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class MemoryPreferences : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
    }

    private val devicePrefs = MemoryPreferences()

    private fun device(social: SocialServices? = null) = run {
        val http = lookupHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }, "MyBooks test")
        DeviceServices(
            lookup = LookupChain(emptyList()),
            authorBooks = GoogleBooksAuthorSearch(http, apiKey = ""),
            subjects = SubjectSearch(http, apiKey = ""),
            social = social,
            releases = ReleasesRepository(ReleasesClient(http), FileSystem.SYSTEM, files.filesDir / "releases"),
            events = ReadingEvents(),
            preferences = devicePrefs,
            clock = { 0L },
        )
    }

    private suspend fun open(id: LibraryId, database: AppDatabase, device: DeviceServices = device()) =
        LibrarySpace(id, database, files, device, scope).also { it.open() }

    @After
    fun tearDown() {
        scope.cancel()
        deleteLibraryDatabase(context, account)
        val local = libraryDatabaseFile(context, LibraryId.Local)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) java.io.File(local.path + suffix).delete()
        FileSystem.SYSTEM.deleteRecursively(files.filesDir)
    }

    @Test
    fun accountLibraryKeepsItsPreferencesInItsDatabase() = runTest(timeout = 30.seconds) {
        val space = open(account, openLibraryDatabase(context, account))
        space.pinnedTags.setPinned("Poetry", true)
        space.hiddenSuggestions.hide("9789720046130")
        space.close()

        val again = open(account, openLibraryDatabase(context, account))

        assertEquals(listOf("Poetry"), again.pinnedTags.tags.value)
        assertEquals(setOf("9789720046130"), again.hiddenSuggestions.keys.value)
        assertNull(devicePrefs.values["home.pinnedTags"])
        assertNull(devicePrefs.values["suggestions.hidden"])
        again.close()
    }

    @Test
    fun noAccountLibraryKeepsItsPreferencesInItsDatabase() = runTest(timeout = 30.seconds) {
        // What the device settings held before libraries were separate is no longer read: the upgrade moved it.
        devicePrefs.values["home.pinnedTags"] = "Poetry"
        val space = open(LibraryId.Local, openLibraryDatabase(context, LibraryId.Local))

        assertEquals(emptyList<String>(), space.pinnedTags.tags.value)
        space.pinnedTags.setPinned("Essays", true)
        space.close()

        val again = open(LibraryId.Local, openLibraryDatabase(context, LibraryId.Local))
        assertEquals(listOf("Essays"), again.pinnedTags.tags.value)
        assertEquals("Poetry", devicePrefs.values["home.pinnedTags"])
        again.close()
    }

    @Test
    fun noAccountLibraryNeverSyncsOrShares() = runTest(timeout = 30.seconds) {
        val social = FakeSocial()
        social.signInAs(social.addUser("ana"))
        val withCommunity = device(social.services)
        val local = open(LibraryId.Local, createDatabase(Room.inMemoryDatabaseBuilder<AppDatabase>(context), AndroidSQLiteDriver()), withCommunity)
        val accountLibrary = open(account, openLibraryDatabase(context, account), withCommunity)

        assertNull(local.sync)
        assertNull(local.sharingStatus)
        assertEquals(false, local.syncRecorder.enabled())
        assertNotNull(accountLibrary.sync)
        assertNotNull(accountLibrary.sharingStatus)
        local.close()
        accountLibrary.close()
    }

    @Test
    fun aPreferenceChangedJustBeforeClosingIsStillQueued() = runTest(timeout = 30.seconds) {
        val social = FakeSocial()
        val database = openLibraryDatabase(context, account)
        // Joined its account's cloud library earlier: changes are queued even before the session is back.
        database.syncStateDao().set("merged:${account.userId}", "1")
        val space = open(account, database, device(social.services))

        space.pinnedTags.setPinned("Poetry", true)
        space.close()

        val again = openLibraryDatabase(context, account)
        assertEquals(listOf(SyncTable.PREFS.wire to "home.pinnedTags"), again.syncQueueDao().batch(10).map { it.table to it.rowId })
        again.close()
    }

    @Test
    fun writesFromAClearedScreenLandBeforeTheLibraryCloses() = runTest(timeout = 30.seconds) {
        val space = open(account, openLibraryDatabase(context, account))
        val ids = listOf("Os Maias", "A Relíquia", "O Primo Basílio").map { title ->
            (space.repository.save(Book(title = title, status = ReadingStatus.READ)) as SaveResult.Saved).id
        }
        // Home, with three books swiped away and Undo still offered…
        val screens = ViewModelStore()
        val home = ViewModelProvider.create(screens, viewModelFactory { initializer { LibraryViewModel(space.repository, appScope = space.writes) } })[LibraryViewModel::class]
        ids.forEach(home::hide)
        val failures = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }

        // …is cleared by a switch (its deletions go ahead), then the library closes.
        screens.clear()
        space.close()

        Thread.setDefaultUncaughtExceptionHandler(before)
        assertEquals(emptyList<Throwable>(), failures)
        val again = openLibraryDatabase(context, account)
        assertEquals(emptyList<Long>(), again.bookDao().getAll().map { it.id })
        again.close()
    }
}
