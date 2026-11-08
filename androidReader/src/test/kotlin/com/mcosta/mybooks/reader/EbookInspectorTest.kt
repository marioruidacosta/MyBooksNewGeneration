package com.mcosta.mybooks.reader

import com.mcosta.mybooks.domain.EbookFormat
import com.mcosta.mybooks.domain.EbookMetadata
import com.mcosta.mybooks.platform.Inspection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sample files are built here: a plain EPUB, EPUBs with Adobe DRM marks, an .acsm ticket, and PDFs. */
class EbookInspectorTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val inspector = EbookInspector()

    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="id">urn:isbn:9789722039994</dc:identifier>
            <dc:title>Os Maias</dc:title>
            <dc:creator opf:file-as="Queirós, Eça de">Eça de Queirós</dc:creator>
            <dc:language>pt</dc:language>
          </metadata>
          <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="c1"/></spine>
        </package>"""

    private fun epub(name: String, extra: Map<String, String> = emptyMap()): File {
        val file = dir.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            // The mimetype entry comes first and stored, as EPUB requires.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            val entries = mapOf(
                "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OEBPS/content.opf" to opf,
                "OEBPS/ch1.xhtml" to "<html><body><p>O Ramalhete…</p></body></html>",
            ) + extra
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
            }
        }
        return file
    }

    private fun text(name: String, content: String) = dir.newFile(name).apply { writeText(content) }

    private val maias = EbookMetadata("Os Maias", listOf("Eça de Queirós"), "9789722039994", "pt")

    /**
     * A hostile EPUB: a few KB on disk, but its package file unpacks to 600 MB. Reading it whole ran out of memory
     * (an Error no catch handled); only its start is read, and the metadata is there.
     */
    @Test
    fun aHugePackageFileIsReadOnlyAtItsStart() = runBlocking {
        val file = dir.newFile("bomb.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write(opf.substringBefore("<manifest>").toByteArray())
            val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(600) { zip.write(spaces) }
            zip.write("</package>".toByteArray())
        }
        assertEquals(Inspection.Readable(EbookFormat.EPUB, maias), inspector.inspect(file, "bomb.epub"))
    }

    @Test
    fun plainEpubIsReadableWithItsMetadata() = runBlocking {
        assertEquals(Inspection.Readable(EbookFormat.EPUB, maias), inspector.inspect(epub("maias.epub"), "maias.epub"))
    }

    @Test
    fun epubWithRightsXmlIsAdobe() = runBlocking {
        val file = epub("drm.epub", mapOf("META-INF/rights.xml" to "<adept:rights xmlns:adept=\"http://ns.adobe.com/adept\"/>"))
        assertEquals(Inspection.AdobeProtected(maias), inspector.inspect(file, "drm.epub"))
    }

    @Test
    fun epubWithAdobeEncryptionIsAdobe() = runBlocking {
        val encryption = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
            <EncryptionMethod Algorithm="http://ns.adobe.com/adept/enc#aes"/><CipherData><CipherReference URI="OEBPS/ch1.xhtml"/></CipherData></EncryptedData></encryption>"""
        assertEquals(Inspection.AdobeProtected(maias), inspector.inspect(epub("enc.epub", mapOf("META-INF/encryption.xml" to encryption)), "enc.epub"))
    }

    @Test
    fun acsmTicketIsAdobeWithItsTitle() = runBlocking {
        val acsm = text("URLLink.acsm", """<?xml version="1.0"?><fulfillmentToken xmlns="http://ns.adobe.com/adept"><resourceItemInfo><metadata>
            <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Os Maias</dc:title>
            <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">Queirós, Eça de</dc:creator></metadata></resourceItemInfo></fulfillmentToken>""")
        assertEquals(Inspection.AdobeProtected(EbookMetadata("Os Maias", listOf("Eça de Queirós"), null, null)), inspector.inspect(acsm, "URLLink.acsm"))
    }

    @Test
    fun plainPdfIsReadableWithItsInfo() = runBlocking {
        val pdf = text("maias.pdf", "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n3 0 obj << /Title (Os Maias) /Author (Eça de Queirós) >> endobj\ntrailer << /Root 1 0 R /Info 3 0 R >>\n%%EOF")
        assertEquals(Inspection.Readable(EbookFormat.PDF, EbookMetadata("Os Maias", listOf("Eça de Queirós"), null, null)), inspector.inspect(pdf, "maias.pdf"))
    }

    @Test
    fun pdfWithAdobeDrmIsAdobe() = runBlocking {
        val pdf = text("drm.pdf", "%PDF-1.6\n9 0 obj << /Filter /EBX_HANDLER /V 4 >> endobj\ntrailer << /Root 1 0 R /Encrypt 9 0 R >>\n%%EOF")
        assertEquals(Inspection.AdobeProtected(null), inspector.inspect(pdf, "drm.pdf"))
    }

    @Test
    fun otherFilesAreUnreadable() = runBlocking {
        assertEquals(Inspection.Unreadable, inspector.inspect(text("notes.txt", "hello"), "notes.txt"))
        assertEquals(Inspection.Unreadable, inspector.inspect(text("empty.epub", ""), "empty.epub"))
    }

    @Test
    fun aZipThatIsNotAnEpubIsUnreadable() = runBlocking {
        val zip = dir.newFile("photos.zip")
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("a.jpg")); it.write(byteArrayOf(1, 2)) }
        assertEquals(Inspection.Unreadable, inspector.inspect(zip, "photos.zip"))
    }
}
