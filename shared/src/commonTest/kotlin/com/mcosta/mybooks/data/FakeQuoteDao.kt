package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.QuoteDao
import com.mcosta.mybooks.data.db.QuoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [QuoteDao], in the same order as the real one. */
class FakeQuoteDao : QuoteDao {
    val rows = MutableStateFlow<Map<Long, QuoteEntity>>(emptyMap())
    private var nextId = 1L

    private val order = compareBy<QuoteEntity> { it.page == null }.thenBy { it.page ?: 0 }.thenBy { it.dateAdded }.thenBy { it.id }

    override fun observeForBook(bookId: Long): Flow<List<QuoteEntity>> =
        rows.map { m -> m.values.filter { !it.deleted && it.bookId == bookId }.sortedWith(order) }

    override suspend fun forBook(bookId: Long) = rows.value.values.filter { !it.deleted && it.bookId == bookId }.sortedWith(order)
    override suspend fun getAll() = rows.value.values.filter { !it.deleted }.sortedWith(compareBy<QuoteEntity> { it.bookId }.then(order))
    override suspend fun getAllAny() = rows.value.values.sortedBy { it.id }
    override suspend fun getByUuid(uuid: String) = rows.value.values.firstOrNull { it.uuid == uuid }

    override suspend fun insert(quote: QuoteEntity): Long {
        val id = nextId++
        rows.value = rows.value + (id to quote.copy(id = id))
        return id
    }

    override suspend fun update(quote: QuoteEntity) {
        if (quote.id in rows.value) rows.value = rows.value + (quote.id to quote)
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value - id
    }

    override suspend fun deleteForBook(bookId: Long) {
        rows.value = rows.value.filterValues { it.bookId != bookId }
    }
}
