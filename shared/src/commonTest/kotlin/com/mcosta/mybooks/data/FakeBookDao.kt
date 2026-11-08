package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.BookDao
import com.mcosta.mybooks.data.db.BookEntity
import com.mcosta.mybooks.domain.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [BookDao]. Search does a plain contains on the folded searchText (no LIKE semantics). */
class FakeBookDao : BookDao {
    val rows = MutableStateFlow<Map<Long, BookEntity>>(emptyMap())
    private var nextId = 1L

    private fun unescape(q: String) = q.replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")

    override fun observeByIsbn(status: ReadingStatus?, isbn: String): Flow<List<BookEntity>> = rows.map { m ->
        m.values.filter { !it.deleted && it.isbn == isbn && (status == null || it.status == status) }.sortedBy { it.title.lowercase() }
    }

    override fun observe(status: ReadingStatus?, query: String): Flow<List<BookEntity>> = rows.map { m ->
        val q = unescape(query)
        m.values
            .filter { !it.deleted }
            .filter { status == null || it.status == status }
            .filter { q.isEmpty() || it.searchText.contains(q) }
            .sortedBy { it.title.lowercase() }
    }

    override fun observeById(id: Long): Flow<BookEntity?> = rows.map { it[id]?.takeIf { b -> !b.deleted } }
    override suspend fun getById(id: Long) = rows.value[id]?.takeIf { !it.deleted }
    override suspend fun findByIsbn(isbn: String) = rows.value.values.firstOrNull { !it.deleted && it.isbn == isbn }
    override suspend fun findByIsbnAny(isbn: String) = rows.value.values.firstOrNull { it.isbn == isbn }
    override suspend fun getByUuid(uuid: String) = rows.value.values.firstOrNull { it.uuid == uuid }
    override suspend fun findWithoutIsbn(title: String, authors: String) = rows.value.values.firstOrNull {
        !it.deleted && it.isbn == null && it.title.equals(title, true) && it.authors.equals(authors, true)
    }

    override suspend fun getAll() = rows.value.values.filter { !it.deleted }.sortedBy { it.title.lowercase() }
    override suspend fun getAllAny() = rows.value.values.sortedBy { it.id }

    override suspend fun insert(book: BookEntity): Long {
        if (book.isbn != null && findByIsbnAny(book.isbn) != null) error("UNIQUE constraint failed: books.isbn")
        val id = nextId++
        rows.value = rows.value + (id to book.copy(id = id))
        return id
    }

    override suspend fun update(book: BookEntity) {
        rows.value = rows.value + (book.id to book)
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value - id
    }
}
