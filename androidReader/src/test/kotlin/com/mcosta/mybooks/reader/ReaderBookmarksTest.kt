package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.Bookmark
import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBookmarksTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val marks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val added = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<Long>()

    private val vm = ReaderViewModel(
        load = { ReaderFile(EbookFormat.EPUB, "/b.epub", null) },
        save = { _, _ -> false },
        markRead = {},
        scope = scope,
        bookmarks = marks,
        addBookmark = { position, label -> added += position to label },
        deleteBookmark = { deleted += it },
    )

    @Test
    fun bookmarkHereUsesChapterOrPageAsLabel() {
        assertEquals("Chapter 3", bookmarkLabel(EbookFormat.EPUB, "Chapter 3", null, 0.4))
        assertEquals("Page 12", bookmarkLabel(EbookFormat.PDF, null, 12, 0.05))
        assertEquals("42%", bookmarkLabel(EbookFormat.EPUB, "", null, 0.424))

        vm.position("{\"p\":7}", 0.4, bookmarkLabel = "Chapter 3")
        vm.bookmarkHere()
        assertEquals(listOf("{\"p\":7}" to "Chapter 3"), added)
    }

    @Test
    fun nothingToBookmarkBeforeThePageShows() {
        vm.bookmarkHere()
        assertEquals(emptyList<Pair<String, String>>(), added)
    }

    @Test
    fun deletingABookmark() {
        vm.deleteBookmark(Bookmark(5, 1, "{}", "Chapter 1", 0))
        assertEquals(listOf(5L), deleted)
    }

    @Test
    fun goingToABookmark() {
        val went = mutableListOf<String>()
        scope.launch { vm.goTo.collect { went += it } }
        vm.open(Bookmark(5, 1, "{\"p\":3}", "Chapter 1", 0))
        assertEquals(listOf("{\"p\":3}"), went)
    }

    @Test
    fun theBookmarksAreShown() {
        marks.value = listOf(Bookmark(1, 1, "{}", "Chapter 1", 0))
        assertEquals(listOf("Chapter 1"), vm.bookmarks.value.map { it.label })
    }
}
