package com.mcosta.mybooks.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.data.db.toEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = LibraryId.Account("user-1")

    @After
    fun tearDown() {
        deleteLibraryDatabase(context, id)
    }

    @Test
    fun accountDatabaseOpensUnderLibrariesFolder() = runTest {
        val dbDir = context.getDatabasePath("mybooks.db").parentFile!!
        val local = File(dbDir, "mybooks.db")
        val localBefore = local.exists()

        val db = openLibraryDatabase(context, id)
        db.bookDao().insert(Book(title = "Dune", authors = "Herbert").toEntity())
        db.close()

        assertTrue(File(dbDir, "libraries/user-1.db").exists())
        val reopened = openLibraryDatabase(context, id)
        assertEquals(1, reopened.bookDao().getAll().size)
        reopened.close()
        assertEquals(localBefore, local.exists())

        deleteLibraryDatabase(context, id)
        assertFalse(File(dbDir, "libraries/user-1.db").exists())
    }
}
