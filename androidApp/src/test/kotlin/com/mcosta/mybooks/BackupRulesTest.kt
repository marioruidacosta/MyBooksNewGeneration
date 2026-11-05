package com.mcosta.mybooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Android's automatic backup keeps the library and its settings, never the community sign-in or caches. */
class BackupRulesTest {
    private fun includes(xml: String, section: String? = null): Set<String> {
        val text = File("src/main/res/xml/$xml").readText()
        val scope = section?.let { text.substringAfter("<$it>").substringBefore("</$it>") } ?: text
        return Regex("""<include domain="(\w+)" path="([^"]+)" />""").findAll(scope).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toSet()
    }

    private val expected = setOf(
        "database:mybooks.db", "database:mybooks.db-wal", "database:mybooks.db-shm",
        "sharedpref:mybooks.xml", "file:covers",
    )

    @Test
    fun theLibraryItsSettingsAndCoverPhotosAreBackedUp() {
        assertEquals(expected, includes("backup_rules.xml"))
        assertEquals(expected, includes("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(expected, includes("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun notTheCommunitySignInNorNewInPortugalsCache() {
        val all = includes("backup_rules.xml") + includes("data_extraction_rules.xml")
        assertFalse(all.any { "community" in it || "releases" in it })
    }

    @Test
    fun theManifestUsesTheRules() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assert("android:fullBackupContent=\"@xml/backup_rules\"" in manifest)
        assert("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }
}
