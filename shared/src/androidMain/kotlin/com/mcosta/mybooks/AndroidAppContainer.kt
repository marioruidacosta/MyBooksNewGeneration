package com.mcosta.mybooks

import android.content.Context
import com.mcosta.mybooks.library.LibraryFiles
import com.mcosta.mybooks.library.deleteLibraryDatabase
import com.mcosta.mybooks.library.libraryDatabaseFile
import com.mcosta.mybooks.library.openLibraryDatabase
import com.mcosta.mybooks.social.SocialConfig
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import java.util.concurrent.TimeUnit
import okio.Path.Companion.toOkioPath

/** The Android app's dependencies, started: the libraries' databases, OkHttp, photos and ebooks under files/. */
fun androidAppContainer(context: Context, googleBooksApiKey: String, appVersion: String, socialConfig: SocialConfig) = AppContainer(
    openDatabase = { id -> openLibraryDatabase(context, id) },
    deleteDatabase = { id -> deleteLibraryDatabase(context, id) },
    databaseFile = { id -> libraryDatabaseFile(context, id).toOkioPath() },
    libraryFiles = LibraryFiles(FileSystem.SYSTEM, context.filesDir.toOkioPath()),
    // OkHttp waits 10 s for data by default; sending the sign-in email can take longer.
    httpEngine = OkHttp.create {
        config {
            readTimeout(30, TimeUnit.SECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
        }
    },
    releasesFileSystem = FileSystem.SYSTEM,
    releasesDir = context.filesDir.toOkioPath() / "releases",
    googleBooksApiKey = googleBooksApiKey,
    userAgent = "MyBooks/$appVersion (Android; personal library app)",
    socialConfig = socialConfig,
    // Session in the app's own preferences (the library default needs an androidx-startup hook).
    socialSettings = SharedPreferencesSettings(context.getSharedPreferences("mybooks_community", Context.MODE_PRIVATE)),
    appSettings = SharedPreferencesSettings(context.getSharedPreferences("mybooks", Context.MODE_PRIVATE)),
).also {
    // Everything after needs the library open. The first start of this version upgrades today's library first (on IO);
    // after that, the library opens without waiting (Room opens the file lazily).
    runBlocking { it.start() }
}
