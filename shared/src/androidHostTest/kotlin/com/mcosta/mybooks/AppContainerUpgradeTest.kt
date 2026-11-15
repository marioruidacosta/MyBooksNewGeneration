package com.mcosta.mybooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.db.toEntity
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.library.LibraryId
import com.mcosta.mybooks.library.libraryDatabaseFile
import com.mcosta.mybooks.library.openLibraryDatabase
import com.mcosta.mybooks.social.SocialConfig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
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
}
