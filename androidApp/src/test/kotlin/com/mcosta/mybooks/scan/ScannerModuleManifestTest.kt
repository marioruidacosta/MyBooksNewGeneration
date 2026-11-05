package com.mcosta.mybooks.scan

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScannerModuleManifestTest {
    /** Without this, the first scan (or quote photo) on a phone fails while Play services downloads the scanner UI (or text recognition). */
    @Test
    fun scannerUiModuleIsRequestedAtInstallTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals("barcode_ui,ocr", info.metaData?.getString("com.google.mlkit.vision.DEPENDENCIES"))
    }
}
