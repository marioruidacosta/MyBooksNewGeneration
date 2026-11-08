package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.BookmarkDao
import com.mcosta.mybooks.data.db.BookmarkEntity
import com.mcosta.mybooks.data.db.EbookDao
import com.mcosta.mybooks.data.db.EbookEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [EbookDao]. */
class FakeEbookDao : EbookDao {
    val rows = MutableStateFlow<Map<Long, EbookEntity>>(emptyMap())

    override fun observe(bookId: Long): Flow<EbookEntity?> = rows.map { it[bookId]?.takeIf { e -> !e.deleted } }
    override suspend fun get(bookId: Long) = rows.value[bookId]?.takeIf { !it.deleted }
    override suspend fun getAll() = rows.value.values.filter { !it.deleted }.sortedBy { it.bookId }
    override suspend fun getAllAny() = rows.value.values.sortedBy { it.bookId }
    override suspend fun getByUuid(uuid: String) = rows.value.values.firstOrNull { it.uuid == uuid }
    /** Makes the next saves fail, like a database error. */
    var failUpsert = false
    override suspend fun upsert(ebook: EbookEntity) {
        check(!failUpsert) { "database error" }
        rows.value = rows.value + (ebook.bookId to ebook)
    }
    override suspend fun delete(bookId: Long) {
        rows.value = rows.value - bookId
    }
    override suspend fun setPosition(bookId: Long, position: String, lastOpened: Long) {
        val row = rows.value[bookId] ?: return
        rows.value = rows.value + (bookId to row.copy(position = position, lastOpened = lastOpened))
    }
}

/** In-memory [BookmarkDao], oldest first like the real one. */
class FakeBookmarkDao : BookmarkDao {
    val rows = MutableStateFlow<Map<Long, BookmarkEntity>>(emptyMap())
    private var nextId = 1L
    private val order = compareBy<BookmarkEntity> { it.dateAdded }.thenBy { it.id }

    override fun observe(bookId: Long): Flow<List<BookmarkEntity>> =
        rows.map { m -> m.values.filter { !it.deleted && it.bookId == bookId }.sortedWith(order) }
    override suspend fun forBook(bookId: Long) = rows.value.values.filter { !it.deleted && it.bookId == bookId }.sortedWith(order)
    override suspend fun getAll() = rows.value.values.filter { !it.deleted }.sortedWith(compareBy<BookmarkEntity> { it.bookId }.then(order))
    override suspend fun getAllAny() = rows.value.values.sortedBy { it.id }
    override suspend fun getByUuid(uuid: String) = rows.value.values.firstOrNull { it.uuid == uuid }
    override suspend fun insert(bookmark: BookmarkEntity): Long {
        val id = nextId++
        rows.value = rows.value + (id to bookmark.copy(id = id))
        return id
    }
    override suspend fun delete(id: Long) {
        rows.value = rows.value - id
    }
    override suspend fun deleteForBook(bookId: Long) {
        rows.value = rows.value.filterValues { it.bookId != bookId }
    }
}
