# Ebook Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read DRM-free EPUB and PDF books inside MyBooks on Android, with position, progress, bookmarks and highlights kept with the library book.

**Architecture:** Data, matching, protection checks, the Gutendex client and the library-side screens live in `shared` (common code). A new Android library module `androidReader` holds Readium, the file inspector and `ReaderActivity`; it plugs into the app through an optional `EbookPlatform` in `PlatformServices` (null on iPhone).

**Tech Stack:** Kotlin 2.4.21, AGP 9.5 (built-in Kotlin), Compose Multiplatform 1.12.2, Room KMP, Ktor, okio, Readium Kotlin toolkit 3.4.0.

**Spec:** `docs/superpowers/specs/2026-11-06-ebook-reader-design.md`

## Global Constraints

- Readium 3.4.0 artifacts, group `org.readium.kotlin-toolkit`: `readium-shared`, `readium-streamer`, `readium-navigator`, `readium-adapter-pdfium`, `readium-navigator-media-tts`. No `readium-lcp`.
- Readium needs core library desugaring (`coreLibraryDesugaring` + `isCoreLibraryDesugaringEnabled = true`) in `androidReader` and `androidApp`.
- Files live in `files/ebooks/<bookId>.<epub|pdf>`; never in backups (Android backup rules include only `file:covers`; MyBooks' backup JSON never contains file bytes).
- One file per book (`ebooks.bookId` is the primary key).
- Database goes from v6 to **v7**; backup JSON stays `version: 1` with optional new fields.
- Gutendex search URL `https://gutendex.com/books/?search=<query>`; timeout 30 s; only `application/epub+zip` downloads are offered.
- Reader progress: `totalProgression` × 100 rounded; finish prompt at ≥ 99 %, once per reading; To read and To buy books move to Reading on first open; Read books keep their status and progress.
- Protected-file copy: "This book is protected with Adobe DRM. Read it in Adobe Digital Editions; MyBooks can still track it." · "This file is protected and can't be opened" · "This file can't be opened as a book" · "Ebook file not on this phone" · "Couldn't open this book".
- Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Gate before each PR: `./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`.

## Review Focus

1. A 100 MB PDF: adding it must stream to disk (okio `Source` → file), never load it into one `ByteArray` (Task 2 test streams 5 MB through `add` and checks size and bytes).
2. A file shared to MyBooks from Gmail or Drive: the content `Uri` permission ends with the Intent, so the file is copied to `cacheDir/incoming/` at once, before any sheet is shown (Task 9 test: the copied file exists after the Intent is handled).
3. Reopening a Read book in the reader: status stays Read, progress and the finish prompt don't change (Task 2 test).
4. A file without a title in its metadata: the book is named after the file name without extension ("Os_Maias.epub" → "Os Maias") (Task 4 test).
5. Gutendex results with authors as "Queirós, Eça de" and books with no EPUB link: authors become "Eça de Queirós"; books without EPUB are left out (Task 5 test).

---

## File Structure

`shared/src/commonMain/kotlin/com/mcosta/mybooks/`
- `domain/Ebook.kt`: `EbookFormat`, `Ebook`, `Bookmark`, `EbookMetadata`, `titleFromFileName`.
- `domain/EbookProtection.kt`: `Protection`, `epubProtection`, `pdfProtection`, `parseAcsm`.
- `data/db/EbookEntity.kt`: `EbookEntity`, `BookmarkEntity`, `EbookDao`, `BookmarkDao`.
- `data/ebooks/EbookFiles.kt`: storage under `files/ebooks/` with okio.
- `data/ebooks/EbookRepository.kt`: add/remove/observe/positions/bookmarks.
- `data/ebooks/EbookMatcher.kt`: file metadata → existing or new book.
- `data/ebooks/ClassicsClient.kt`: Gutendex search and download.
- `platform/PlatformServices.kt` (modify): `EbookPlatform`, `IncomingFile`, `Inspection`.
- `ui/ebooks/EbookCard.kt`, `ui/ebooks/AddEbookScreen.kt` + `AddEbookViewModel.kt`, `ui/ebooks/ClassicsScreen.kt` + `ClassicsViewModel.kt`, `ui/ebooks/PendingFiles.kt`.
- Modified: `data/db/AppDatabase.kt`, `data/db/QuoteEntity.kt`, `domain/Quote.kt`, `data/BookRepository.kt`, `data/backup/BackupSerializer.kt`, `AppContainer.kt`, `ui/MyBooksNavHost.kt`, `ui/home/HomeScreen.kt`, `ui/detail/BookDetailScreen.kt`.

`androidReader/` (new module, namespace `com.mcosta.mybooks.reader`)
- `AndroidEbookPlatform.kt`, `EbookInspector.kt`, `ReaderActivity.kt`, `ReaderViewModel.kt`, `ui/ReaderBars.kt`, `ui/ReaderSheets.kt` (contents, bookmarks, Aa, search), `Highlights.kt`, `ReadAloud.kt`.

`androidApp/` (modify): `build.gradle.kts`, `AndroidManifest.xml` (intent filters, `ReaderActivity`), `MainActivity.kt` (incoming files).

---

## PR 1 — Ebooks in the library

### Task 1: Ebook data and database v7

**Files:**
- Create: `shared/.../domain/Ebook.kt`, `shared/.../data/db/EbookEntity.kt`
- Modify: `shared/.../data/db/AppDatabase.kt`, `shared/.../data/db/QuoteEntity.kt`, `shared/.../domain/Quote.kt`
- Test: `shared/src/androidHostTest/.../data/db/MigrationTest.kt`, `shared/src/commonTest/.../data/FakeEbookDao.kt` (+ `FakeBookmarkDao`)

**Interfaces:**
- Produces: `enum class EbookFormat { EPUB, PDF }`; `data class Ebook(bookId: Long, format: EbookFormat, fileName: String, size: Long, dateAdded: Long, position: String? = null, lastOpened: Long? = null)`; `data class Bookmark(id: Long = 0, bookId: Long, position: String, label: String, dateAdded: Long)`; `Quote.position: String? = null`.
- `EbookDao`: `observe(bookId): Flow<EbookEntity?>`, `get(bookId): EbookEntity?`, `getAll(): List<EbookEntity>`, `upsert(EbookEntity)`, `delete(bookId)`, `setPosition(bookId, position: String, lastOpened: Long)`.
- `BookmarkDao`: `observe(bookId): Flow<List<BookmarkEntity>>` (by dateAdded), `forBook(bookId)`, `getAll()`, `insert(BookmarkEntity): Long`, `delete(id)`, `deleteForBook(bookId)`.
- `AppDatabase.ebookDao()`, `bookmarkDao()`; `MIGRATION_6_7`.

- [ ] **Step 1: Write the failing migration test** `upgradeTo7AddsEbooksBookmarksAndQuotePositions`: v1 database upgraded; `ebookDao().getAll()` and `bookmarkDao().getAll()` empty; insert an ebook and a bookmark for Dune, read them back; a quote with `position = "{\"href\":\"ch1.xhtml\"}"` round-trips, an old quote has `position == null`.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests "*MigrationTest*"` — expect compile failure.
- [ ] **Step 3: Implement** entities, DAOs, `version = 7`, `MIGRATION_6_7` (two `CREATE TABLE` statements copied from the generated `schemas/…/7.json`, index on `bookmarks.bookId`, `ALTER TABLE quotes ADD COLUMN position TEXT`), `Quote.position` in `QuoteEntity` mapping, and the fakes.
- [ ] **Step 4: Run** the test and `:shared:testAndroidHostTest` — PASS; `shared/schemas/…/7.json` exists.
- [ ] **Step 5: Commit** "Ebooks: database v7 (ebooks, bookmarks, quote positions)".

### Task 2: Ebook files and repository

**Files:**
- Create: `shared/.../data/ebooks/EbookFiles.kt`, `shared/.../data/ebooks/EbookRepository.kt`
- Modify: `shared/.../data/BookRepository.kt` (`delete` also calls `ebooks?.removeAll(id)`), `AppContainer.kt`
- Test: `shared/src/commonTest/.../data/ebooks/EbookRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 DAOs and models.
- Produces:
  - `class EbookFiles(fs: FileSystem, dir: Path)`: `suspend fun store(bookId: Long, format: EbookFormat, source: Source): Long` (bytes written; replaces), `fun path(bookId, format): Path`, `fun exists(bookId, format): Boolean`, `fun delete(bookId, format)`.
  - `class EbookRepository(ebooks: EbookDao, bookmarks: BookmarkDao, files: EbookFiles, books: BookRepository, clock: () -> Long)`:
    `observe(bookId): Flow<EbookState?>` where `data class EbookState(val ebook: Ebook, val fileOnPhone: Boolean)`;
    `suspend fun add(bookId, format, fileName, source: Source)`; `suspend fun remove(bookId)` (file + row; bookmarks and quotes stay);
    `suspend fun removeAll(bookId)` (file, row, bookmarks; used when a book is deleted);
    `suspend fun opened(bookId)` (To read/To buy → Reading via `books.setStatus`);
    `suspend fun savePosition(bookId, position: String, progression: Double): Boolean` (true when the finish prompt should show);
    `suspend fun finishPromptShown(bookId)`; bookmarks: `observeBookmarks`, `addBookmark(bookId, position, label)`, `deleteBookmark(id)`.
  - `AppContainer.ebooks: EbookRepository` with files in `filesDir/ebooks`.

- [ ] **Step 1: Write failing tests** in `EbookRepositoryTest`:
  - `addingStreamsTheFileAndRecordsIt`: 5 MB source → `size == 5_242_880`, file bytes equal, `fileOnPhone`.
  - `aSecondFileReplacesTheFirst` (one row, new format and size, old file gone).
  - `removeKeepsBookmarksAndQuotes`, `deletingTheBookDeletesEverything` (via `BookRepository.delete`).
  - `openingMovesToReadAndToBuyToReading` (dateStarted set), `openingAReadBookChangesNothing`.
  - `positionBecomesProgress` (0.424 → 42), `finishPromptAt99OnceUntilShown` (0.985 → false, 0.99 → true, after `finishPromptShown` 1.0 → false), `aReadBookKeepsItsProgress` (Review Focus 3).
  - `missingFileIsReported` (row present, file deleted → `fileOnPhone == false`).
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests "*EbookRepositoryTest*"` — FAIL (unresolved).
- [ ] **Step 3: Implement.** "Once per reading": an in-memory set of book ids prompted since the book last went below 99 %.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** "Ebooks: files on the phone and the ebook repository".

### Task 3: Backups

**Files:**
- Modify: `shared/.../data/backup/BackupSerializer.kt`, `shared/.../data/BookRepository.kt` (`exportJson`, `importJson`, `restoreQuotes`)
- Test: `shared/src/commonTest/.../data/ebooks/EbookBackupTest.kt`

**Interfaces:**
- Consumes: Task 1 DAOs.
- Produces: `BookRepository(…, ebooks: EbookDao? = null, bookmarks: BookmarkDao? = null)`; backup book fields `ebook: {format, fileName, size, position, dateAdded}?`, `bookmarks: [{position, label, dateAdded}]`, quote `position`.

- [ ] **Step 1: Failing test** `backupKeepsPositionsBookmarksAndHighlightsButNoFile`: export a book with an ebook row (position set), two bookmarks and a quote with a position; the JSON doesn't contain the file bytes; importing into an empty repository restores the ebook row (so the book shows "Ebook file not on this phone"), both bookmarks once even when imported twice, and the quote's position.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; an ebook row that already exists is not replaced by an import.
- [ ] **Step 4: Run** `:shared:testAndroidHostTest` — PASS.
- [ ] **Step 5: Commit** "Ebooks: positions, bookmarks and highlights in backups".

### Task 4: Matching, protection and `.acsm`

**Files:**
- Create: `shared/.../data/ebooks/EbookMatcher.kt`, `shared/.../domain/EbookProtection.kt`
- Modify: `shared/.../domain/Ebook.kt` (`EbookMetadata`, `titleFromFileName`)
- Test: `shared/src/commonTest/.../domain/EbookProtectionTest.kt`, `.../data/ebooks/EbookMatcherTest.kt`

**Interfaces:**
- Produces:
  - `data class EbookMetadata(title: String?, authors: List<String>, isbn: String?, language: String?)`.
  - `fun titleFromFileName(name: String): String` ("Os_Maias.epub" → "Os Maias", "a-b.pdf" → "a-b").
  - `sealed interface Protection { None; Adobe; Other }`; `fun epubProtection(hasRightsXml: Boolean, encryptionXml: String?): Protection` (Adobe when `rights.xml` exists or an `EncryptionMethod` `Algorithm` contains `adobe.com`; Other for any other encrypted `CipherReference` except font obfuscation algorithms `http://www.idpf.org/2008/embedding` and `http://ns.adobe.com/pdf/enc#RC`); `fun pdfProtection(trailerAndHead: String): Protection` (Adobe when `/Encrypt` is present with `/Filter /EBX_HANDLER` or `/Filter /ADBE`; Other when `/Encrypt` is present otherwise).
  - `fun parseAcsm(xml: String): EbookMetadata?` (`dc:title`, `dc:creator`; null when not an `fulfillmentToken`).
  - `class EbookMatcher(books: BookRepository)`: `suspend fun match(meta: EbookMetadata, fileName: String): Match` with `sealed interface Match { data class Existing(val book: Book); data class New(val book: Book) }`.

- [ ] **Step 1: Failing tests**: `rightsXmlIsAdobe`, `adobeEncryptionIsAdobe`, `fontObfuscationIsNotProtection`, `otherEncryptionIsOther`, `pdfEbxIsAdobe`, `plainPdfIsNone`, `acsmGivesTitleAndAuthor`, `notAnAcsmIsNull`; matcher: `isbnMatchesFirst`, `titleAndFirstAuthorMatch` (case-insensitive, like `findWithoutIsbn`), `unknownBecomesNew` (status To read, authors joined with ", "), `noTitleUsesFileName` (Review Focus 4).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; XML read with simple regexes like `GoodreadsCsv` (no XML library in common code).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** "Ebooks: matching files to books, and spotting protected files".

### Task 5: Free classics client

**Files:**
- Create: `shared/.../data/ebooks/ClassicsClient.kt`
- Test: `shared/src/commonTest/.../data/ebooks/ClassicsClientTest.kt`, fixture `shared/src/commonTest/fixtures/gutendex_search.json` (a trimmed real response, 3 books, one without EPUB)

**Interfaces:**
- Produces: `data class Classic(id: Int, title: String, authors: List<String>, languages: List<String>, epubUrl: String, coverUrl: String?)`; `class ClassicsClient(http: HttpClient, base: String = "https://gutendex.com/books/")`: `suspend fun search(query: String): List<Classic>` (throws `ClassicsUnavailable` on network errors, non-2xx or 30 s timeout), `suspend fun download(classic: Classic, into: suspend (Source) -> Unit)`.
- `AppContainer.classics`.

- [ ] **Step 1: Failing tests** with `MockEngine`: `searchParsesBooks`, `authorsAreFirstNameFirst` ("Queirós, Eça de" → "Eça de Queirós"), `booksWithoutEpubAreLeftOut` (Review Focus 5), `queryIsEncoded` ("os maias" → `search=os%20maias`), `serverErrorAndTimeoutThrowUnavailable`, `downloadStreams`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; EPUB link from `formats["application/epub+zip"]`, cover from `formats["image/jpeg"]`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** "Ebooks: searching and downloading free classics".

### Task 6: Platform interface and library screens

**Files:**
- Modify: `shared/.../platform/PlatformServices.kt`, `AppContainer.kt`, `ui/MyBooksNavHost.kt`, `ui/home/HomeScreen.kt`, `ui/detail/BookDetailScreen.kt`
- Create: `shared/.../ui/ebooks/EbookCard.kt`, `PendingFiles.kt`, `AddEbookViewModel.kt`, `AddEbookScreen.kt`, `ClassicsViewModel.kt`, `ClassicsScreen.kt`
- Test: `shared/src/commonTest/.../ui/ebooks/AddEbookViewModelTest.kt`, `ClassicsViewModelTest.kt`; screenshots in `androidApp/src/test/.../ScreenshotTest.kt` (`ebook-card`, `classics`, `protected`)

**Interfaces:**
- Produces:
  - `data class IncomingFile(val path: String, val fileName: String, val size: Long)` (a temporary copy the app owns);
  - `sealed interface Inspection { data class Readable(val format: EbookFormat, val metadata: EbookMetadata); data class AdobeProtected(val metadata: EbookMetadata?); data object Protected; data object Unreadable }`;
  - `interface EbookPlatform { suspend fun pickFile(): IncomingFile?; suspend fun inspect(file: IncomingFile): Inspection; fun openReader(bookId: Long) }`; `PlatformServices.ebooks: EbookPlatform? = null`.
  - `class PendingFiles` (in-memory, like `CatalogBooks`): `put(IncomingFile): String`, `take(key): IncomingFile?`.
  - Routes `add-ebook/{key}`, `classics`.
  - `AddEbookViewModel(file, inspect, matcher, ebooks, books)` states: `Checking`, `Ready(match, format)`, `AdobeProtected(match?)`, `Protected(match?)`, `Unreadable`, `Done(bookId, read: Boolean)`; actions `readNow()`, `addToRead()`, `addWithoutFile()`, `replaceConfirmed()` (when the matched book already has a file).
  - `ClassicsViewModel(client, ebooks, books)`: `search(q)`, states `Idle | Searching | Results(list) | Failed`, `download(classic)` → `Downloaded(bookId)`; a failed download removes the book it created.
- Home › +: "Add an ebook" (pick → inspect → `add-ebook`), "Free classics".
- Book page: Read button + Ebook card when `EbookState` exists and the platform reader exists; "Add ebook file" otherwise (hidden when `services.ebooks == null`).

- [ ] **Step 1: Failing VM tests**: `readableFileMatchedThenReadNow` (book Reading, file stored, `Done(read = true)`), `addToReadKeepsStatus`, `adobeFileAddsWithoutFile` (no ebook row), `acsmUsesItsTitle`, `unreadableShowsMessage`, `replacingAsksFirst`; classics: `searchShowsResults`, `slowSearchShowsSearchingThenResults`, `failedSearchCanRetry`, `downloadCreatesToReadBookWithFile`, `failedDownloadLeavesNoBook`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** VMs, screens (copy strings from Global Constraints), routes, Home menu, Ebook card.
- [ ] **Step 4: Run** `:shared:testAndroidHostTest` and `:androidApp:testDebugUnitTest`; look at the three new screenshots.
- [ ] **Step 5: Commit** "Ebooks: adding ebooks, free classics and the book page card".

### Task 7: `androidReader` module and file inspector

**Files:**
- Create: `androidReader/build.gradle.kts`, `androidReader/src/main/AndroidManifest.xml`, `androidReader/src/main/kotlin/com/mcosta/mybooks/reader/EbookInspector.kt`, `AndroidEbookPlatform.kt`
- Modify: `settings.gradle.kts` (`:androidReader`), `gradle/libs.versions.toml` (`readium = "3.4.0"`, the five artifacts, `desugar-jdk-libs`, plugin `android-library`), `androidApp/build.gradle.kts` (depend on `:androidReader`, desugaring), `androidApp/.../MainActivity.kt` (pass `AndroidEbookPlatform(this)` into `androidPlatformServices`)
- Test: `androidReader/src/test/kotlin/.../EbookInspectorTest.kt` (Robolectric; sample files built in the test: a minimal EPUB zip with `mimetype`, `META-INF/container.xml`, `content.opf` with `dc:title`/`dc:creator`/`dc:identifier` ISBN, `ch1.xhtml`; the same with `rights.xml`; with Adobe `encryption.xml`; an `.acsm`; a one-page PDF; the PDF with an `/Encrypt /Filter /EBX_HANDLER` trailer)

**Interfaces:**
- Consumes: Task 4 functions, Task 6 `EbookPlatform`, `IncomingFile`, `Inspection`.
- Produces: `class EbookInspector(context)`: `suspend fun inspect(file: File, fileName: String): Inspection` (zip/PDF bytes checked with Task 4 functions first; metadata of readable files through Readium `AssetRetriever` + `PublicationOpener`); `class AndroidEbookPlatform(activity: ComponentActivity, results: ActivityResults)` implementing `EbookPlatform` (`pickFile` with `OpenDocument` for `application/epub+zip`, `application/pdf`, `application/vnd.adobe.adept+xml`, `application/octet-stream`; copies to `cacheDir/incoming/`; `openReader` starts `ReaderActivity`).

- [ ] **Step 1: Failing tests**: one per sample file, expecting `Readable(EPUB, metadata with title/author/ISBN)`, `AdobeProtected`, `AdobeProtected`, `AdobeProtected(metadata from acsm)`, `Readable(PDF, …)`, `AdobeProtected`; a text file → `Unreadable`.
- [ ] **Step 2: Run** `./gradlew :androidReader:testDebugUnitTest` — FAIL.
- [ ] **Step 3: Implement**; module uses `com.android.library` (AGP built-in Kotlin, like `androidApp`), compose, `compileSdk = 37`, `minSdk = 26`.
- [ ] **Step 4: Run** — PASS; `:androidApp:assembleDebug` builds; APK size noted in the PR.
- [ ] **Step 5: Commit** "Ebooks: reader module with Readium and the file inspector".

### Task 8: Basic reader

**Files:**
- Create: `androidReader/.../ReaderActivity.kt`, `ReaderViewModel.kt`, `ui/ReaderBars.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml` (`ReaderActivity`, not exported)

**Interfaces:**
- Consumes: `EbookRepository.opened`, `savePosition`, `finishPromptShown`, `observe`; container through `(application as ContainerOwner).container` — add `interface ContainerOwner { val container: AppContainer }` in `shared/src/androidMain` and implement it in `MyBooksApp`.
- Produces: `ReaderActivity.start(context, bookId)`; intent extra `book-id`.

- [ ] **Step 1: Write `ReaderViewModelTest`** (JVM, no Readium): `savesPositionAtMostEvery3sAndOnLeaving` (positions at t=0,1,2,4 s → saved twice, plus once on `leave()`), `finishPromptOnceThenMarkAsRead`, `missingFileClosesWithMessage` ("Couldn't open this book").
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**: open the publication (EPUB → `EpubNavigatorFragment`, PDF → `PdfNavigatorFragment` with `PdfiumEngineProvider`), restore `Locator` from the saved position JSON, collect `currentLocator` → `ReaderViewModel.position(locator.toJSON(), totalProgression)`; bars in a `ComposeView` over the fragment container; tap in the middle toggles bars; the book page's Read button calls `services.ebooks.openReader(id)`.
- [ ] **Step 4: Run** the tests and the gate; **on the phone**: open a Gutenberg EPUB and a small PDF, turn pages, leave and reopen (same page), check progress on the book page, reach the end → "Finished? Mark as Read".
- [ ] **Step 5: Commit** "Ebooks: reading EPUB and PDF, with position and progress".

### Task 9: Open with MyBooks

**Files:**
- Modify: `androidApp/src/main/AndroidManifest.xml` (intent filters on `MainActivity`: `VIEW` and `SEND` for `application/epub+zip`, `application/pdf`, `application/vnd.adobe.adept+xml`), `androidApp/.../MainActivity.kt`
- Test: `androidApp/src/test/kotlin/.../OpenWithTest.kt`

**Interfaces:**
- Consumes: `PendingFiles.put`, route `add-ebook/{key}`, `MainActivity.OPENABLE` (add `add-ebook/[a-z0-9-]{1,40}`).
- Produces: `MainActivity.incomingFile(intent): IncomingFile?` (copies the content `Uri` to `cacheDir/incoming/` immediately).

- [ ] **Step 1: Failing tests**: the manifest resolves `ACTION_VIEW` with `application/epub+zip` and `application/pdf` to `MainActivity`, not `image/png`; `sharedFileIsCopiedAtOnce` (Robolectric content provider with an EPUB → file in `cacheDir/incoming/` with the same bytes, Review Focus 2); `nonBookTypesAreIgnored`; the existing `BackupRulesTest` still passes unchanged (its exact include set keeps `files/ebooks` out of Android's backup).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the gate; **on the phone**: open an EPUB from Files and share a PDF from Gmail into MyBooks.
- [ ] **Step 5: Commit** "Ebooks: open and share EPUB, PDF and .acsm files with MyBooks"; open **PR 1**.

## PR 2 — A comfortable reader

### Task 10: Look and feel, contents, position slider

**Files:**
- Create: `androidReader/.../ui/ReaderSheets.kt` (Aa sheet, contents sheet)
- Modify: `ReaderActivity.kt`, `ReaderViewModel.kt`, `ui/ReaderBars.kt`

**Interfaces:**
- Produces: `data class ReadingLook(fontSize: Double = 1.0, font: String? = null, lineHeight: Double? = null, theme: LookTheme = LIGHT)`, `enum LookTheme { LIGHT, SEPIA, DARK }`, saved as one value in `Preferences` under `reader.look`; mapped to `EpubPreferences` (`publisherStyles = false` when `lineHeight` is set).

- [ ] **Step 1: Failing tests**: `lookIsRememberedForAllBooks` (encode/decode through a fake `Preferences`), `positionLabel` ("Chapter 3 · 42%" for EPUB with a chapter title, "Page 12 of 240" for PDF).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; contents from `publication.tableOfContents` (PDF outline when present, else hidden); slider seeks with `navigator.go(locator from progression)`; Aa hidden for PDF.
- [ ] **Step 4: Run**; **on the phone**: change size, font, spacing and theme; jump through contents; drag the slider.
- [ ] **Step 5: Commit** "Reader: look and feel, contents and position slider".

### Task 11: Bookmarks

**Files:**
- Modify: `ui/ReaderSheets.kt` (bookmarks sheet), `ReaderViewModel.kt`, `ui/ReaderBars.kt`

- [ ] **Step 1: Failing tests**: `bookmarkHereUsesChapterOrPageAsLabel`, `deletingABookmark`, `goingToABookmark` (VM emits the locator to navigate to).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the gate; **on the phone**: add, open and delete bookmarks in an EPUB and a PDF.
- [ ] **Step 5: Commit** "Reader: bookmarks"; open **PR 2**.

## PR 3 — Reading tools (EPUB only)

### Task 12: Highlights as quotes

**Files:**
- Create: `androidReader/.../Highlights.kt`
- Modify: `ReaderActivity.kt`, `ReaderViewModel.kt`, `shared/.../data/QuoteRepository.kt` (`addHighlight(bookId, text, position, label): Long`, `observeHighlights(bookId)` = quotes with a position)

**Interfaces:**
- Produces: selection action mode item "Highlight" (`EpubNavigatorFragment` `SelectableNavigator.currentSelection()`); decorations group `"highlights"` built from quotes with a position; tapping a decoration opens the quote dialog (change or delete).

- [ ] **Step 1: Failing tests**: `highlightBecomesQuoteWithChapter` (text trimmed, label = chapter title, `page = null`), `deletingTheQuoteRemovesTheHighlight`, `typedQuotesAreNotDrawn`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run**; **on the phone**: highlight, see it on the book page's Quotes, edit and delete it from the reader.
- [ ] **Step 5: Commit** "Reader: highlights become quotes".

### Task 13: Search

**Files:**
- Modify: `ui/ReaderSheets.kt` (search sheet), `ReaderViewModel.kt`

- [ ] **Step 1: Failing tests**: `searchResultsShowTheWordsAround` (from fake `Locator.Text` before/highlight/after → "…before **match** after…"), `noResults`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** with Readium's `SearchService` (`publication.search(query)`), results paged 20 at a time; button hidden for PDF.
- [ ] **Step 4: Run**; **on the phone**: search a word, open a result.
- [ ] **Step 5: Commit** "Reader: search".

### Task 14: Read aloud

**Files:**
- Create: `androidReader/.../ReadAloud.kt`
- Modify: `ReaderActivity.kt`, `ui/ReaderBars.kt`

- [ ] **Step 1: Failing tests**: `speedSteps` (0.75, 1.0, 1.25, 1.5, 2.0 cycling), `readAloudHiddenForPdf`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** with `AndroidTtsNavigatorFactory`; the utterance's locator is decorated (group `"tts"`) and the navigator follows it; play/pause/speed in a small bar; stops when leaving the reader.
- [ ] **Step 4: Run** the gate; **on the phone**: play, pause, change speed, follow the text across a page turn.
- [ ] **Step 5: Commit** "Reader: read aloud"; open **PR 3**.
