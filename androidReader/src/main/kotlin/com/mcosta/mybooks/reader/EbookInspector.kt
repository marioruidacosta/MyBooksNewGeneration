package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.domain.Isbn
import com.mcosta.mybooks.domain.Protection
import com.mcosta.mybooks.domain.epubProtection
import com.mcosta.mybooks.domain.parseAcsm
import com.mcosta.mybooks.domain.pdfProtection
import com.mcosta.mybooks.domain.personName
import com.mcosta.mybooks.platform.Inspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * What a file handed to MyBooks is: an EPUB or PDF it can read (with the title, authors and ISBN the file gives),
 * one protected with Adobe DRM (or an Adobe `.acsm` ticket), otherwise protected, or not a book at all.
 * Only the file's own bytes are read; nothing is sent anywhere.
 */
class EbookInspector {
    suspend fun inspect(file: File, fileName: String): Inspection = withContext(Dispatchers.IO) {
        try {
            inspectNow(file)
        } catch (e: Exception) {
            Inspection.Unreadable
        }
    }

    private fun inspectNow(file: File): Inspection {
        if (file.length() == 0L) return Inspection.Unreadable
        val head = file.inputStream().use { it.readNBytes(5) }
        return when {
            head.startsWith("PK") -> epub(file)
            head.startsWith("%PDF") -> pdf(file)
            else -> parseAcsm(readText(file, 0, ACSM_MAX))?.let { Inspection.AdobeProtected(it) } ?: Inspection.Unreadable
        }
    }

    private fun epub(file: File): Inspection = ZipFile(file).use { zip ->
        val container = zip.text("META-INF/container.xml") ?: return Inspection.Unreadable
        val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)?.groupValues?.get(1) ?: return Inspection.Unreadable
        val meta = zip.text(opfPath)?.let(::opfMetadata) ?: EbookMetadata(null, emptyList(), null, null)
        when (epubProtection(zip.getEntry("META-INF/rights.xml") != null, zip.text("META-INF/encryption.xml"))) {
            Protection.None -> Inspection.Readable(EbookFormat.EPUB, meta)
            Protection.Adobe -> Inspection.AdobeProtected(meta)
            Protection.Other -> Inspection.Protected
        }
    }

    private fun pdf(file: File): Inspection {
        // The head and the end hold the trailer, the security handler and, usually, the document's info.
        val length = file.length()
        val bytes = if (length <= PDF_ENDS * 2) file.readBytes() else readBytes(file, 0, PDF_ENDS) + readBytes(file, length - PDF_ENDS, PDF_ENDS)
        val latin = String(bytes, Charsets.ISO_8859_1)
        return when (pdfProtection(latin)) {
            Protection.Adobe -> Inspection.AdobeProtected(null)
            Protection.Other -> Inspection.Protected
            Protection.None -> {
                // An encrypted PDF's info strings are encrypted too: only a plain one's are read.
                val encrypted = "/Encrypt" in latin
                val title = if (encrypted) null else pdfString(bytes, latin, "Title")
                val author = if (encrypted) null else pdfString(bytes, latin, "Author")
                Inspection.Readable(EbookFormat.PDF, EbookMetadata(title, listOfNotNull(author?.let(::personName)), null, null))
            }
        }
    }

    private companion object {
        const val ACSM_MAX = 256 * 1024
        const val PDF_ENDS = 128 * 1024

        fun ByteArray.startsWith(prefix: String) = size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

        fun ZipFile.text(name: String): String? = getEntry(name)?.let { e -> getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

        fun readBytes(file: File, from: Long, count: Int): ByteArray = RandomAccessFile(file, "r").use { f ->
            f.seek(from)
            ByteArray(minOf(count.toLong(), f.length() - from).toInt()).also { f.readFully(it) }
        }

        fun readText(file: File, from: Long, count: Int) = String(readBytes(file, from, count), Charsets.UTF_8)

        fun xml(xml: String, tag: String): List<String> =
            Regex("""<(?:\w+:)?$tag\b[^>]*>([\s\S]*?)</(?:\w+:)?$tag>""").findAll(xml)
                .map { it.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim() }
                .filter { it.isNotEmpty() }
                .toList()

        /** Title, creators, an ISBN among the identifiers, and language from an EPUB's package document. */
        fun opfMetadata(opf: String): EbookMetadata {
            val isbn = xml(opf, "identifier").firstNotNullOfOrNull { id ->
                Isbn.normalize(id.substringAfterLast(':').filter { it.isDigit() || it == 'X' || it == 'x' })
            }
            return EbookMetadata(xml(opf, "title").firstOrNull(), xml(opf, "creator").map(::personName), isbn, xml(opf, "language").firstOrNull())
        }

        /** A `/Key (text)` string from a PDF's info: UTF-16 with its byte mark, else UTF-8, else PDF's Latin-like encoding. */
        fun pdfString(bytes: ByteArray, latin: String, key: String): String? {
            val m = Regex("""/$key\s*\(((?:\\.|[^\\)])*)\)""").find(latin) ?: return null
            val raw = m.groupValues[1].replace(Regex("""\\([()\\])"""), "$1")
            val data = raw.toByteArray(Charsets.ISO_8859_1)
            val text = when {
                data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> String(data, 2, data.size - 2, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                } catch (e: CharacterCodingException) {
                    raw
                }
            }
            return text.trim().takeIf { it.isNotEmpty() }
        }
    }
}
