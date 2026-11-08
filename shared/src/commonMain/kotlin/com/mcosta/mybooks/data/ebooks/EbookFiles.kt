package com.mcosta.mybooks.data.ebooks

import com.mcosta.mybooks.domain.EbookFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/** Ebook files on the phone: `<dir>/<bookId>.<epub|pdf>`, written in pieces so big PDFs never sit in memory. */
class EbookFiles(
    private val fs: FileSystem,
    private val dir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun path(bookId: Long, format: EbookFormat): Path = dir / "$bookId.${format.extension}"

    fun exists(bookId: Long, format: EbookFormat): Boolean = fs.exists(path(bookId, format))

    /** Writes [source] as the book's file (replacing it) and returns its size. A failed write leaves no file. */
    suspend fun store(bookId: Long, format: EbookFormat, source: Source): Long = withContext(io) {
        fs.createDirectories(dir)
        val target = path(bookId, format)
        val partial = dir / "$bookId.${format.extension}.part"
        try {
            fs.sink(partial).buffer().use { it.writeAll(source) }
            fs.atomicMove(partial, target)
        } finally {
            fs.delete(partial, mustExist = false)
        }
        fs.metadata(target).size ?: 0L
    }

    fun delete(bookId: Long, format: EbookFormat) = fs.delete(path(bookId, format), mustExist = false)
}
