package com.mcosta.mybooks.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EbookProtectionTest {
    private fun encryption(vararg algorithms: String) = """<?xml version="1.0"?>
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
        ${algorithms.joinToString("\n") { "<enc:EncryptedData><enc:EncryptionMethod Algorithm=\"$it\"/><enc:CipherData><enc:CipherReference URI=\"OEBPS/ch1.xhtml\"/></enc:CipherData></enc:EncryptedData>" }}
        </encryption>"""

    @Test
    fun rightsXmlIsAdobe() = assertEquals(Protection.Adobe, epubProtection(hasRightsXml = true, encryptionXml = encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")))

    @Test
    fun adobeEncryptionIsAdobe() = assertEquals(Protection.Adobe, epubProtection(false, encryption("http://ns.adobe.com/adept/enc#aes")))

    @Test
    fun fontObfuscationIsNotProtection() {
        assertEquals(Protection.None, epubProtection(false, encryption("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")))
        assertEquals(Protection.None, epubProtection(false, null))
    }

    @Test
    fun otherEncryptionIsOther() = assertEquals(Protection.Other, epubProtection(false, encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc")))

    @Test
    fun pdfEbxIsAdobe() {
        assertEquals(Protection.Adobe, pdfProtection("trailer << /Encrypt 12 0 R >> 12 0 obj << /Filter /EBX_HANDLER /V 4 >>"))
        assertEquals(Protection.Adobe, pdfProtection("12 0 obj << /Filter /ADBE_PubSec >> trailer << /Encrypt 12 0 R >>"))
    }

    /** A PDF locked only against printing or copying (standard handler) opens in any viewer: not protected here. */
    @Test
    fun plainAndPermissionsOnlyPdfsAreNone() {
        assertEquals(Protection.None, pdfProtection("%PDF-1.7 1 0 obj << /Type /Catalog >> trailer << /Root 1 0 R >>"))
        assertEquals(Protection.None, pdfProtection("trailer << /Encrypt 9 0 R >> 9 0 obj << /Filter /Standard /V 2 >>"))
    }

    @Test
    fun otherPdfSecurityIsOther() = assertEquals(Protection.Other, pdfProtection("trailer << /Encrypt 9 0 R >> 9 0 obj << /Filter /FOPN_foweb >>"))

    @Test
    fun acsmGivesTitleAndAuthor() {
        val acsm = """<?xml version="1.0"?><fulfillmentToken fulfillmentType="buy" xmlns="http://ns.adobe.com/adept">
            <resourceItemInfo><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">A Cidade &amp; as Serras</dc:title>
            <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">Queirós, Eça de</dc:creator>
            <dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/">urn:isbn:9789722036504</dc:identifier>
            <dc:format xmlns:dc="http://purl.org/dc/elements/1.1/">application/epub+zip</dc:format></metadata></resourceItemInfo></fulfillmentToken>"""
        assertEquals(EbookMetadata("A Cidade & as Serras", listOf("Eça de Queirós"), "9789722036504", null), parseAcsm(acsm))
    }

    @Test
    fun notAnAcsmIsNull() = assertNull(parseAcsm("<html><body>Hello</body></html>"))

    @Test
    fun namesAndTitles() {
        assertEquals("Eça de Queirós", personName("Queirós, Eça de"))
        assertEquals("José Saramago", personName(" José Saramago "))
        assertEquals("Os Maias", titleFromFileName("Os_Maias.epub"))
        assertEquals("a-b", titleFromFileName("a-b.pdf"))
        assertEquals("Memorial do Convento", titleFromFileName("Memorial do Convento"))
    }

    /** Catalogue names with a third part stay one author: a suffix is kept, a title or dates are dropped. */
    @Test
    fun threePartNamesStayOneAuthor() {
        assertEquals("Martin Luther King Jr.", personName("King, Martin Luther, Jr."))
        assertEquals("Leo Tolstoy", personName("Tolstoy, Leo, graf"))
        assertEquals("José Saramago", personName("Saramago, José, 1922-2010"))
        assertEquals("John Smith III", personName("Smith, John, III"))
        assertEquals("Homer", personName("Homer,"))
        for (raw in listOf("King, Martin Luther, Jr.", "Tolstoy, Leo, graf", "A, B, C, D")) assertFalse(',' in personName(raw), raw)
    }
}
