package com.mcosta.mybooks.physical

import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.FakeBookDao
import com.mcosta.mybooks.data.PinnedTags
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.data.suggestions.HiddenSuggestions
import com.mcosta.mybooks.library.MemoryPreferences
import com.mcosta.mybooks.sync.LibraryPrefs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookcaseStoreTest {
    private val prefs = MemoryPreferences()
    private val changed = mutableListOf<String>()
    private val persisted = mutableListOf<String>()
    private val store = BookcaseStore(prefs, { prefs.values.keys }, { changed += it }, { key, _ -> persisted += key })
    private val window = Bookcase("x", "By the window", "Living room", listOf(BookcaseColumn("c1", listOf("s1"))))

    private fun repoWith(bookcases: BookcaseStore) = BookRepository(
        FakeBookDao(),
        object : BookLookupService {
            override suspend fun lookup(isbn: String) = LookupResult.NotFound
        },
        bookcases = bookcases,
    )

    @Test
    fun saveAndRemoveSync() = runTest {
        store.save(window)
        assertEquals(window.toStored(), prefs.values["bookcase.x"])
        assertEquals(listOf("bookcase.x"), changed)
        assertEquals(listOf("bookcase.x"), persisted)
        assertEquals(listOf(window), store.bookcases.value)

        store.remove("x")
        assertEquals(listOf("bookcase.x"), store.keys, "a removal stays listed, so it reaches the other devices")
        assertNull(store.value("bookcase.x"))
        assertTrue(store.bookcases.value.isEmpty())
        assertEquals(2, changed.size)
    }

    @Test
    fun aReadOnlyBookcaseIsNeverSavedBack() = runTest {
        val newer = """{"v":2,"name":"n","room":"r","columns":[{"id":"c","shelves":["s"]}]}"""
        store.pulled("bookcase.y", newer)
        store.save(store.bookcases.value.single())
        assertEquals(newer, prefs.values["bookcase.y"])
        assertTrue(changed.isEmpty())
    }

    @Test
    fun pulledValuesShowWithoutReportingAChange() {
        store.pulled("bookcase.x", window.toStored())
        assertEquals(listOf(window), store.bookcases.value)
        store.pulled("bookcase.x", null)
        assertTrue(store.bookcases.value.isEmpty())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun reloadReadsWhatIsStored() {
        prefs.values["bookcase.x"] = window.toStored()
        prefs.values["bookcase.bad"] = "nope"
        prefs.values["home.pinnedTags"] = "a"
        store.reload()
        assertEquals(listOf(window), store.bookcases.value)
    }

    @Test
    fun anOlderAppIgnoresTheKey() {
        val older = LibraryPrefs(PinnedTags(MemoryPreferences()), HiddenSuggestions(MemoryPreferences()))
        older.set("bookcase.x", window.toStored())
        assertNull(older.get("bookcase.x"))
        assertTrue("bookcase.x" !in older.keys)
    }

    @Test
    fun libraryPrefsListsBookcases() = runTest {
        val synced = LibraryPrefs(PinnedTags(MemoryPreferences()), HiddenSuggestions(MemoryPreferences()), bookcases = store)
        store.save(window)
        assertTrue("bookcase.x" in synced.keys)
        assertEquals(window.toStored(), synced.get("bookcase.x"))
        synced.set("bookcase.x", null)
        assertTrue(store.bookcases.value.isEmpty())
        assertNull(synced.get("bookcase.x"))
    }

    @Test
    fun backupCarriesBookcases() = runTest {
        store.save(window)
        val text = repoWith(store).exportJson()

        val otherPrefs = MemoryPreferences()
        val other = BookcaseStore(otherPrefs, { otherPrefs.values.keys }, {})
        other.save(window.copy(name = "Changed"))
        other.save(window.copy(id = "z", name = "Stays"))
        repoWith(other).importJson(text)
        assertEquals("By the window", other.bookcases.value.first { it.id == "x" }.name)
        assertEquals("Stays", other.bookcases.value.first { it.id == "z" }.name)
    }

    @Test
    fun aBackupWithoutBookcasesLeavesTheLayout() = runTest {
        store.save(window)
        repoWith(store).importJson("""{"version":1,"books":[]}""")
        assertEquals(listOf(window), store.bookcases.value)
    }

    @Test
    fun anUnreadableBookcaseInABackupIsSkipped() = runTest {
        repoWith(store).importJson("""{"version":1,"books":[],"bookcases":[{"id":"x","value":"nope"}]}""")
        assertTrue(store.bookcases.value.isEmpty())
        assertNull(prefs.values["bookcase.x"])
    }

    @Test
    fun adoptIfMissingKeepsWhatIsThere() = runTest {
        store.save(window)
        store.adoptIfMissing("x", window.copy(name = "Other").toStored())
        assertEquals("By the window", store.bookcases.value.single().name)
        store.adoptIfMissing("y", window.copy(id = "y", name = "New").toStored())
        assertEquals(2, store.bookcases.value.size)
    }
}
