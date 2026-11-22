package com.mcosta.mybooks.share

import com.mcosta.mybooks.data.FakeShareStateDao
import com.mcosta.mybooks.data.backup.BackupShare
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.domain.ReadingStatus
import com.mcosta.mybooks.library.MemoryPreferences
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DUNE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val EMMA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val DUNE_KEY = SHARE_EXCLUDED_PREFIX + DUNE

/** "Don't share this book", the same on every device. */
class ShareExclusionsTest {
    private val shares = FakeShareStateDao()
    private val prefs = MemoryPreferences()
    private val recorded = mutableListOf<String>()
    private var pendingCalls = 0
    private val uuids = mutableMapOf(1L to DUNE, 2L to EMMA)
    private var failNextChange = false
    private val events = mutableListOf<String>()
    private val exclusions = ShareExclusions(
        prefs, { prefs.values.keys },
        onChanged = {
            if (failNextChange) {
                failNextChange = false
                error("no database")
            }
            events += "queued $it"
            recorded += it
        },
        persist = { key, value -> events += "saved $key=$value" },
    )
    private val tracker = ShareTracker(
        shares, activeOwner = { "ana" }, onPending = { pendingCalls++ }, uuidOf = { uuids[it] },
        exclusions = exclusions, bookIdOf = { uuid -> uuids.entries.firstOrNull { it.value == uuid }?.key },
    )
    private val dune = Book(id = 1, title = "Dune", status = ReadingStatus.READ)

    @Test
    fun excludingABookSyncsIt() = runTest {
        tracker.setExcluded(dune, true)
        assertEquals("1", tracker.exclusionValue(DUNE_KEY))
        assertEquals(listOf(DUNE_KEY), recorded)
    }

    @Test
    fun sharingItAgainSyncsTheRemoval() = runTest {
        tracker.setExcluded(dune, true)
        recorded.clear()
        tracker.setExcluded(dune, false)
        assertNull(tracker.exclusionValue(DUNE_KEY), "a null value is a deleted row, which reaches the other devices")
        assertEquals(listOf(DUNE_KEY), tracker.exclusionKeys())
        assertEquals(listOf(DUNE_KEY), recorded)
    }

    @Test
    fun anExclusionFromAnotherDeviceAppliesHereWithoutGoingBack() = runTest {
        tracker.storeExclusion(DUNE_KEY, "1")
        tracker.applyExclusion(DUNE_KEY)
        val row = shares.get(1)!!
        assertTrue(row.excluded)
        assertFalse(row.pending, "the server already took the public copy down")
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aBookExcludedElsewhereIsNotPublishedWhenEditedHere() = runTest {
        tracker.storeExclusion(DUNE_KEY, "1")
        tracker.applyExclusion(DUNE_KEY)
        tracker.changed(dune, dune.copy(rating = 5))
        assertFalse(shares.get(1)!!.pending)
        assertEquals(0, pendingCalls)
    }

    @Test
    fun aRemovalFromAnotherDeviceAppliesHere() = runTest {
        tracker.setExcluded(dune, true)
        shares.upsert(shares.get(1)!!.copy(pending = false))
        recorded.clear()
        tracker.storeExclusion(DUNE_KEY, null)
        tracker.applyExclusion(DUNE_KEY)
        assertFalse(shares.get(1)?.excluded == true)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anExclusionPulledBeforeItsBookAppliesWhenTheBookArrives() = runTest {
        uuids.remove(1)
        tracker.storeExclusion(DUNE_KEY, "1")
        tracker.applyExclusion(DUNE_KEY)
        assertNull(shares.get(1))
        uuids[1] = DUNE
        tracker.syncedFromElsewhere(dune)
        assertTrue(shares.get(1)!!.excluded)
    }

    @Test
    fun exclusionsMadeBeforeTheySyncedAreCarriedOverOnce() = runTest {
        tracker.setExcluded(dune, true)
        prefs.values.clear()
        recorded.clear()
        tracker.carryExclusionsOver()
        assertEquals(listOf(DUNE_KEY), recorded)
        assertEquals("1", tracker.exclusionValue(DUNE_KEY))

        // Removed on another device afterwards: a later start must not bring it back.
        tracker.storeExclusion(DUNE_KEY, null)
        recorded.clear()
        tracker.carryExclusionsOver()
        assertTrue(recorded.isEmpty())
        assertNull(tracker.exclusionValue(DUNE_KEY))
    }

    @Test
    fun theWebsiteIsToldOnlyOnceTheExclusionsAreCarriedOverAndInTheCloud() = runTest {
        tracker.vouchForExclusions()
        assertNull(tracker.exclusionValue(SHARE_EXCLUSIONS_SYNCED), "nothing uploaded yet")
        tracker.carryExclusionsOver()
        assertNull(tracker.exclusionValue(SHARE_EXCLUSIONS_SYNCED), "a first sign-in may still move them to cloud ids")
        tracker.vouchForExclusions()
        assertEquals("1", tracker.exclusionValue(SHARE_EXCLUSIONS_SYNCED))
        assertTrue(SHARE_EXCLUSIONS_SYNCED in tracker.exclusionKeys())
        assertEquals(listOf(SHARE_EXCLUSIONS_SYNCED), recorded)
        tracker.vouchForExclusions()
        assertEquals(1, recorded.size, "said once")
    }

    @Test
    fun anInterruptedCarryOverUploadsTheExclusionNextTime() = runTest {
        tracker.setExcluded(dune, true)
        prefs.values.clear()
        recorded.clear()
        failNextChange = true
        assertFailsWith<IllegalStateException> { tracker.carryExclusionsOver() }
        assertTrue(recorded.isEmpty())
        tracker.carryExclusionsOver()
        assertEquals(listOf(DUNE_KEY), recorded)
    }

    @Test
    fun theValueReachesTheDiskBeforeItIsQueued() = runTest {
        tracker.setExcluded(dune, true)
        assertEquals(listOf("saved $DUNE_KEY=1", "queued $DUNE_KEY"), events)
    }

    @Test
    fun aPublicCopyUnderAnotherIdIsTakenDownWhenExcludedElsewhere() = runTest {
        // A restored backup keeps its old public id: the server can't always tell it is this book.
        tracker.changed(null, dune)
        shares.upsert(shares.get(1)!!.copy(shareId = "old-public-id", pending = false))
        pendingCalls = 0
        tracker.storeExclusion(DUNE_KEY, "1")
        tracker.applyExclusion(DUNE_KEY)
        val row = shares.get(1)!!
        assertTrue(row.excluded)
        assertTrue(row.pending, "the Publisher must unpublish the old id")
        assertEquals(1, pendingCalls)
    }

    @Test
    fun aBookArrivingExcludedAlsoTakesDownACopyUnderAnotherId() = runTest {
        tracker.changed(null, dune)
        shares.upsert(shares.get(1)!!.copy(shareId = "old-public-id", pending = false))
        pendingCalls = 0
        tracker.storeExclusion(DUNE_KEY, "1")
        tracker.syncedFromElsewhere(dune)
        assertTrue(shares.get(1)!!.pending)
        assertEquals(1, pendingCalls)
    }

    @Test
    fun anOldRemovalForAnAdoptedUuidKeepsThisPhonesExclusion() = runTest {
        // First sign-in: the book took its cloud twin's uuid; the share state moves to it only after the pull.
        tracker.setExcluded(dune, true)
        shares.upsert(shares.get(1)!!.copy(shareId = "this-phones-id", pending = false))
        tracker.storeExclusion(DUNE_KEY, null)
        tracker.whileJoiningCloud { tracker.applyExclusion(DUNE_KEY) }
        assertTrue(shares.get(1)!!.excluded)
    }

    @Test
    fun aRemovalAfterTheFirstSignInClearsABookUnderAnotherId() = runTest {
        // A restored book, excluded and then shared again on the website: this phone must follow.
        tracker.setExcluded(dune, true)
        shares.upsert(shares.get(1)!!.copy(shareId = "old-public-id", pending = false))
        tracker.storeExclusion(DUNE_KEY, null)
        tracker.applyExclusion(DUNE_KEY)
        assertFalse(shares.get(1)?.excluded == true)
    }

    @Test
    fun aRestoredExclusionSyncs() = runTest {
        tracker.restored(1, BackupShare("share-1", excluded = true))
        assertEquals("1", tracker.exclusionValue(DUNE_KEY))
        assertEquals(listOf(DUNE_KEY), recorded)
    }
}
