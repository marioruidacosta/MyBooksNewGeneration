package com.mcosta.mybooks

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** Library sync retries at once when the phone gets a network back, instead of waiting out its retry delay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncConnectivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aNetworkComingBackRetriesSync() {
        val manager = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        // The app may have registered its own already (when the community is configured).
        val before = manager.networkCallbacks.toSet()
        var restored = 0
        followConnectivity(context) { restored++ }
        val added = manager.networkCallbacks - before
        assertEquals(1, added.size)

        added.single().onAvailable(ShadowNetwork.newInstance(7))

        assertEquals(1, restored)
    }
}
