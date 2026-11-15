package com.mcosta.mybooks

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults
import androidx.compose.ui.window.ComposeUIViewController
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.deleteLibraryDatabase
import com.mcosta.mybooks.library.libraryDatabasePath
import com.mcosta.mybooks.library.openLibraryDatabase
import com.mcosta.mybooks.platform.IosNativeServices
import com.mcosta.mybooks.platform.iosPlatformServices
import com.mcosta.mybooks.social.SocialConfig
import com.mcosta.mybooks.ui.App
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/** The app's Documents folder (backed up by iOS, survives updates). */
@OptIn(ExperimentalForeignApi::class)
private fun documentsPath(): String =
    requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, true, null)?.path
    ) { "No Documents directory" }

private fun infoString(key: String): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String).orEmpty()

/** Created once for the app's lifetime, started. */
private val container: AppContainer by lazy {
    val documents = documentsPath()
    AppContainer(
        openDatabase = { id -> openLibraryDatabase(documents, id) },
        deleteDatabase = { id -> deleteLibraryDatabase(documents, id) },
        databaseFile = { id -> libraryDatabasePath(documents, id).toPath() },
        libraryFiles = LibraryFiles(FileSystem.SYSTEM, documents.toPath()),
        // Sending the sign-in email can take longer than the default wait.
        httpEngine = Darwin.create { configureSession { timeoutIntervalForRequest = 30.0 } },
        releasesFileSystem = FileSystem.SYSTEM,
        releasesDir = "$documents/releases".toPath(),
        googleBooksApiKey = infoString("GoogleBooksApiKey").trim(),
        userAgent = "MyBooks/${infoString("CFBundleShortVersionString")} (iOS; personal library app)",
        socialConfig = SocialConfig(infoString("SupabaseUrl").trim(), infoString("SupabaseAnonKey").trim()),
        appSettings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults),
    ).also {
        // Everything after needs the library open. The first start of this version upgrades today's library first (on
        // IO; the session is stored where it can't be read at once here, so of several owners the last recorded wins).
        runBlocking { it.start() }
    }
}

/** Entry point used by the Swift app: the whole shared UI in a UIViewController. */
@Suppress("FunctionName", "unused")
fun MainViewController(native: IosNativeServices): UIViewController {
    val services = iosPlatformServices(native)
    return ComposeUIViewController { App(container, services) }
}
