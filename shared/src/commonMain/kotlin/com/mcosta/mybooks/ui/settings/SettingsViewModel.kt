package com.mcosta.mybooks.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcosta.mybooks.data.Appearance
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.CoverFinder
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.ui.today as currentDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: BookRepository,
    private val documents: DocumentPicker,
    private val today: () -> String = { currentDate().toString() },
    /** Looks up missing covers in the background; null in tests that don't involve it. */
    private val covers: CoverFinder? = null,
    /** Book or wallpaper colours; null in tests that don't involve it. */
    private val appearance: Appearance? = null,
) : ViewModel() {
    /** Whether the app takes its colours from the wallpaper. */
    val followWallpaper: StateFlow<Boolean> = appearance?.followWallpaper ?: MutableStateFlow(false)

    fun setFollowWallpaper(follow: Boolean) {
        appearance?.setFollowWallpaper(follow)
    }
    /** The current or last cover search. */
    val coverProgress: StateFlow<CoverFinder.Progress?> = covers?.progress ?: MutableStateFlow(null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun export() = run("Export failed") {
        val backup = repo.exportBackup()
        if (!documents.save("mybooks-backup-${today()}.zip", "application/zip", backup)) return@run null
        val count = repo.bookCount()
        "Library exported ($count ${if (count == 1) "book" else "books"})"
    }

    fun import() = run("Import failed") {
        val bytes = documents.open(listOf("application/zip", "application/json", "text/plain")) ?: return@run null
        val s = repo.importBackup(bytes)
        "Imported: ${s.added} added, ${s.updated} updated, ${s.failed} failed"
    }

    fun exportGoodreads() = run("Export failed") {
        val csv = repo.exportGoodreads()
        if (!documents.save("goodreads-${today()}.csv", "text/csv", csv.encodeToByteArray())) return@run null
        val count = repo.bookCount()
        "Exported $count ${if (count == 1) "book" else "books"} for Goodreads. Upload it on goodreads.com: My Books, Import and export."
    }

    fun importGoodreads() = run("Import failed") {
        // Android file managers often label .csv files as Excel.
        val bytes = documents.open(listOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "text/plain")) ?: return@run null
        val s = repo.importGoodreads(bytes.decodeToString())
        val summary = "Imported from Goodreads: ${s.added} added, ${s.updated} updated, ${s.failed} skipped"
        if (covers == null) return@run summary
        covers.start()
        "$summary.\n\nLooking for covers now. It takes a few minutes and carries on if you leave this screen."
    }

    /** Looks up covers for books with an ISBN and no cover. */
    fun findCovers() {
        covers?.start()
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Runs [action]; its message (null = cancelled, say nothing) or the failure is shown. */
    private fun run(failurePrefix: String, action: suspend () -> String?) {
        viewModelScope.launch {
            val message = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$failurePrefix: ${e.message ?: e::class.simpleName}"
            }
            if (message != null) _message.value = message
        }
    }
}
