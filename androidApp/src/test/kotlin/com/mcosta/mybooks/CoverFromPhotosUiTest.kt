package com.mcosta.mybooks

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mcosta.mybooks.data.BookRepository
import com.mcosta.mybooks.data.covers.CoverStore
import com.mcosta.mybooks.data.covers.PhotoShrinker
import com.mcosta.mybooks.data.db.createAndroidDatabase
import com.mcosta.mybooks.data.lookup.BookLookupService
import com.mcosta.mybooks.data.lookup.LookupResult
import com.mcosta.mybooks.domain.Book
import com.mcosta.mybooks.platform.CoverCamera
import com.mcosta.mybooks.platform.CoverPhotoPicker
import com.mcosta.mybooks.platform.DocumentPicker
import com.mcosta.mybooks.platform.IsbnScanner
import com.mcosta.mybooks.platform.PlatformServices
import com.mcosta.mybooks.platform.ScanOutcome
import com.mcosta.mybooks.ui.LocalCoverStore
import com.mcosta.mybooks.ui.LocalPlatformServices
import com.mcosta.mybooks.ui.detail.BookDetailScreen
import com.mcosta.mybooks.ui.detail.BookDetailViewModel
import com.mcosta.mybooks.ui.edit.EditBookScreen
import com.mcosta.mybooks.ui.edit.EditBookViewModel
import com.mcosta.mybooks.ui.theme.MyBooksTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Choosing an existing photo as a book's cover, next to taking one with the camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverFromPhotosUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "cover-photos-${System.nanoTime()}.db"
    private val db = createAndroidDatabase(context, dbName)
    private val repo = BookRepository(db.bookDao(), object : BookLookupService {
        override suspend fun lookup(isbn: String): LookupResult = LookupResult.NotFound
    })
    private var cameraOpened = 0
    private var pickerOpened = 0

    private fun services(withPicker: Boolean) = PlatformServices(
        scanner = object : IsbnScanner { override suspend fun scan() = ScanOutcome.Cancelled },
        camera = object : CoverCamera {
            override suspend fun takePhoto(): ByteArray? { cameraOpened++; return null }
        },
        shrinker = object : PhotoShrinker { override fun shrink(photo: ByteArray, maxSide: Int) = photo },
        documents = object : DocumentPicker {
            override suspend fun save(suggestedName: String, mime: String, bytes: ByteArray) = false
            override suspend fun open(mimeTypes: List<String>): ByteArray? = null
        },
        photoPicker = if (!withPicker) null else object : CoverPhotoPicker {
            override suspend fun pickPhoto(): ByteArray { pickerOpened++; return byteArrayOf(1) }
        },
    )

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun show(withPicker: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalCoverStore provides CoverStore(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "covers"),
                LocalPlatformServices provides services(withPicker),
            ) { MyBooksTheme { content() } }
        }
    }

    private fun idleUntil(ready: () -> Boolean) =
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); ready() }

    private fun bookWithoutCover(): Long = runBlocking {
        repo.findByTitleAndAuthors("Tinta e sangue", "Han Kang")?.id
            ?: (repo.save(Book(title = "Tinta e sangue", authors = "Han Kang")) as com.mcosta.mybooks.data.SaveResult.Saved).id
    }

    private fun bookPage(withPicker: Boolean): Long {
        val id = bookWithoutCover()
        val vm = BookDetailViewModel(repo, id, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        show(withPicker) { BookDetailScreen(vm, onBack = {}, onEdit = {}, onRead = {}, onAddEbookFile = {}) }
        idleUntil { vm.book.value != null }
        return id
    }

    @Test
    fun choosingFromPhotosSetsTheCover() {
        val id = bookPage(withPicker = true)
        compose.onNodeWithText("Add a cover photo").performClick()
        compose.onNodeWithText("Choose from photos").performClick()
        idleUntil { runBlocking { repo.getBook(id)?.coverUrl.orEmpty().startsWith(CoverStore.PREFIX) } }
        assertEquals(1, pickerOpened)
        assertEquals(0, cameraOpened)
    }

    @Test
    fun withoutAPickerTheCameraOpensDirectly() {
        bookPage(withPicker = false)
        compose.onNodeWithText("Add a cover photo").performClick()
        idleUntil { cameraOpened == 1 }
        assertTrue(compose.onAllNodesWithTextCount("Choose from photos") == 0)
    }

    @Test
    fun theEditFormOffersChoosePhoto() {
        val id = bookWithoutCover()
        val vm = EditBookViewModel(repo, id, null)
        show(withPicker = true) { EditBookScreen(vm, isNew = false, onBack = {}, onSaved = {}) }
        idleUntil { vm.state.value.form.title.isNotEmpty() }
        compose.onNodeWithText("Choose photo").assertExists()
    }

    @Test
    fun theEditFormHidesChoosePhotoWithoutAPicker() {
        val id = bookWithoutCover()
        val vm = EditBookViewModel(repo, id, null)
        show(withPicker = false) { EditBookScreen(vm, isNew = false, onBack = {}, onSaved = {}) }
        idleUntil { vm.state.value.form.title.isNotEmpty() }
        compose.onNodeWithText("Take photo").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("Choose photo") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
