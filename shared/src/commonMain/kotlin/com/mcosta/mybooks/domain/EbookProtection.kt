package com.mcosta.mybooks.domain

/** Whether a file can be read here: [Adobe] DRM (Adobe Digital Editions), or [Other] protection. */
enum class Protection { None, Adobe, Other }

/** Font obfuscation (IDPF's and Adobe's): a normal part of DRM-free EPUBs, not protection. */
private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
private val ALGORITHM = Regex("""Algorithm\s*=\s*["']([^"']+)["']""")

/**
 * An EPUB's protection from its `META-INF`: `rights.xml` is Adobe DRM; an `encryption.xml` encrypting content
 * (not only obfuscating fonts) is Adobe when an Adobe algorithm is named, other protection otherwise.
 */
fun epubProtection(hasRightsXml: Boolean, encryptionXml: String?): Protection {
    if (hasRightsXml) return Protection.Adobe
    val algorithms = encryptionXml?.let { xml -> ALGORITHM.findAll(xml).map { it.groupValues[1].trim() }.toList() }.orEmpty()
        .filterNot { it in FONT_OBFUSCATION }
    return when {
        algorithms.isEmpty() -> Protection.None
        algorithms.any { "adobe.com" in it } -> Protection.Adobe
        else -> Protection.Other
    }
}

/**
 * A PDF's protection, from text of its file (the head and the trailer are enough): Adobe's DRM handlers
 * (`/EBX_HANDLER`, `/ADBE…`) are Adobe; the standard handler (usually only a permissions password, which every
 * viewer opens) is none; any other security handler is other protection.
 */
fun pdfProtection(text: String): Protection {
    if ("/Encrypt" !in text) return Protection.None
    val filter = Regex("""/Filter\s*/([A-Za-z0-9_.]+)""").findAll(text).map { it.groupValues[1] }.toList()
    return when {
        filter.any { it == "EBX_HANDLER" || it.startsWith("ADBE") } -> Protection.Adobe
        filter.isEmpty() || filter.any { it == "Standard" } -> Protection.None
        else -> Protection.Other
    }
}

private fun xmlText(xml: String, tag: String): List<String> =
    // [\s\S] rather than DOT_MATCHES_ALL, which isn't available in common (iOS) Kotlin.
    Regex("""<(?:\w+:)?$tag\b[^>]*>([\s\S]*?)</(?:\w+:)?$tag>""").findAll(xml)
        .map { it.groupValues[1].replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&").trim() }
        .filter { it.isNotEmpty() }
        .toList()

/** The title, authors and ISBN in an Adobe `.acsm` download ticket; null when [xml] isn't one. */
fun parseAcsm(xml: String): EbookMetadata? {
    if (!Regex("""<(?:\w+:)?fulfillmentToken\b""").containsMatchIn(xml)) return null
    val isbn = xmlText(xml, "identifier").firstNotNullOfOrNull { id ->
        id.removePrefix("urn:").removePrefix("isbn:").removePrefix("ISBN:").filter { it.isDigit() || it == 'X' || it == 'x' }.takeIf { it.length in setOf(10, 13) }
    }
    return EbookMetadata(xmlText(xml, "title").firstOrNull(), xmlText(xml, "creator").map(::personName), isbn, xmlText(xml, "language").firstOrNull())
}
