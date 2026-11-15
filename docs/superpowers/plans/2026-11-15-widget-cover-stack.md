# Reading Widget: a Stack of Covers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The home-screen widget shows a flickable stack of cards, one per book being read. Each card has its cover, and the challenge row stays below the stack.

**Architecture:**
- `ReadingSummary` gains the ordered list of books being read.
- A `WidgetCovers` helper in androidApp turns a book into a cover bitmap. It uses the book's photo if there is one, otherwise its web cover (downloaded once, shrunk and cached), otherwise a generated cover.
- The widget layout uses a `StackView` filled by a `RemoteViewsService`/`RemoteViewsFactory`. Tapping a card goes through a click template with a per-card fill-in intent.

**Tech Stack:** Android `AppWidgetProvider`, `RemoteViews`, `StackView`, `RemoteViewsService`, Robolectric (`GraphicsMode.NATIVE`), Kotlin Multiplatform shared module.

**Spec:** `docs/superpowers/specs/2026-11-15-widget-cover-stack-design.md`

## Global Constraints

- **Copy.** The header reads "Reading" when one book is being read, and "Reading · N books" when there are N > 1. The empty card keeps today's text: "Nothing on the go" / "Pick your next book from To read".
- **Order.** Cards are ordered most recently started first. That is the order `readingSummary` already sorts by: `dateStarted` descending, then `dateAdded` descending.
- **Covers.**
  - A cover photo (`cover:` ref) is read via `CoverStore`.
  - A web cover is downloaded once, shrunk with `AndroidPhotoShrinker.shrink(bytes, maxSide = 300)`, and kept in `cacheDir/widget-covers/`.
  - Anything else, including a failed download, gets a generated cover. A card is never blank.
- **Widget size.** The widget info changes to `targetCellHeight="3"` and `minHeight="180dp"`. It stays resizable.
- **Refresh.** On every summary change (the existing `follow`), call `notifyAppWidgetViewDataChanged(ids, R.id.widget_stack)` as well as `updateAppWidget`.
- **Network.** No download on the main thread. Downloads happen only in `RemoteViewsFactory.getViewAt`, which runs on a binder thread, with 10 s connect and read timeouts.
- **Gate.** Wrap every run in `timeout`.
  - First: `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && timeout 1800 ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`
  - Then: `timeout 1800 ./gradlew :androidReader:lintDebug :androidApp:lintDebug :shared:compileKotlinIosSimulatorArm64 -q`.
- **Robolectric tests use `@Config(sdk = [35])`.** The newest SDK image fails on Java 25.
- **No suspend extension on an indexed Room `@Entity`.** It crashes the Kotlin/Native compiler.
- **Line endings.** Keep each file's existing line endings.

## Review Focus

1. **Offline, or a cover site that is down, when the widget first shows.** The card shows a generated cover; no blank card, no hang. Pinned by Task 2 `aFailedDownloadGivesTheGeneratedCover`.
2. **A large web cover** (a 1–3 MB image). It must be shrunk before it is kept and handed to the widget, because widget bitmaps have a small memory budget. Pinned by Task 2 `aDownloadedCoverIsShrunkAndKept`.
3. **The same cover shown again** (every refresh rebuilds every card). It is read from the cache with no second download. Pinned by Task 2 `aCachedCoverIsNotDownloadedAgain`.
4. **Many books being read** (more than 3). Every one gets a card, in order. Pinned by Task 3 `everyBookBeingReadGetsACardInOrder`.
5. **A book with no progress.** The card hides the progress row instead of showing 0%. Pinned by Task 3 `aCardWithoutProgressHidesTheBar`.

---

### Task 1: The list of books being read

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/domain/ReadingSummary.kt`
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/components/GeneratedCover.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/domain/ReadingSummaryTest.kt`

**Interfaces:**
- **Produces `ReadingSummary(val reading: List<Book>, val challenge: ChallengeProgress?)`**:
  - `reading` holds every book with status `READING`, in the existing sort order.
  - Keep `val current: Book? get() = reading.firstOrNull()`.
  - Replace the `alsoReading` constructor parameter with `val alsoReading: Int get() = (reading.size - 1).coerceAtLeast(0)`, so existing readers keep working.
  - Update every constructor call to the new shape. `RoundFourAndroidTest` builds one.
- **Produces `fun coverColorArgb(title: String): Int`** in `GeneratedCover.kt`. It is public and returns `COVER_COLORS[coverColorIndex(title)].toArgb()`, so androidApp can draw the same colour. (`COVER_COLORS` is internal to shared.)

- [ ] **Step 1: Write failing tests.**
  - `everyBookBeingReadInOrder`: three books being read with `dateStarted` 1, 3, 2, plus one book that is read. Assert `summary.reading.map { it.title } == listOf("Third", "Second", "First")`, `summary.current?.title == "Third"` and `summary.alsoReading == 2`.
  - `coverColourMatchesTheGeneratedCover`: assert `coverColorArgb("Stoner") == COVER_COLORS[coverColorIndex("Stoner")].toArgb()`.
- [ ] **Step 2: Run** `timeout 900 ./gradlew :shared:testAndroidHostTest --tests '*ReadingSummaryTest*' -q`. Expected: FAIL (`reading` and `coverColorArgb` are undefined).
- [ ] **Step 3: Implement** the new `ReadingSummary` shape and `coverColorArgb`. Update the callers.
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit** "Reading summary: every book being read".

### Task 2: Cover bitmaps for the widget

**Files:**
- Create: `androidApp/src/main/kotlin/com/mcosta/mybooks/widget/WidgetCovers.kt`
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/widget/WidgetCoversTest.kt` (Robolectric, `@GraphicsMode(NATIVE)`)

**Interfaces:**
- Consumes: `CoverStore` (`isLocal`, `read`), `AndroidPhotoShrinker`, and `coverColorArgb` from Task 1.
- **Produces:**
  - `class WidgetCovers(private val cacheDir: File, private val store: () -> CoverStore, private val shrinker: PhotoShrinker = AndroidPhotoShrinker, private val download: (String) -> ByteArray? = ::downloadCover)`, with `fun bitmapFor(book: Book): Bitmap`. It blocks, so it is called from `getViewAt` only.
    - It tries, in order: the photo via `store().read(ref)`; then the cached file `widget-covers/<sha1(url)>.jpg`; then `download(url)`, which is shrunk with `maxSide = 300` and written to that file; then the generated cover.
    - It never returns null and never throws.
  - `internal fun downloadCover(url: String): ByteArray?`. It uses `HttpURLConnection` with 10 s connect and read timeouts, and returns null on any failure or on a non-2xx status.
  - `fun generatedCoverBitmap(title: String, authors: String, width: Int = 200, height: Int = 300): Bitmap`.
    - The background is `coverColorArgb(title)`.
    - The spine is the left 7%, filled with black at 22% alpha.
    - The title is serif, white, wrapped, near the top. The author is smaller, near the bottom.

- [ ] **Step 1: Write failing tests.** Use a temp `cacheDir`, `CoverStore` on `FakeFileSystem`, a recording fake shrinker that returns a real small JPEG, and a counting fake `download`.
  - `aPhotoCoverIsReadFromTheStore`: a book with a saved `cover:` ref. Assert the bitmap is non-null and `download` was called 0 times.
  - `aDownloadedCoverIsShrunkAndKept`: a web URL whose download returns bytes. Assert the shrinker got `maxSide == 300` and the cache dir holds one `.jpg`.
  - `aCachedCoverIsNotDownloadedAgain`: call `bitmapFor` twice for the same book. Assert `download` was called once.
  - `aFailedDownloadGivesTheGeneratedCover`: the download returns null. Assert the bitmap's centre-right pixel equals `coverColorArgb(title)`, and nothing is cached.
  - `noCoverGivesTheGeneratedCover`: `coverUrl = null`. Assert the same pixel check, and `download` was called 0 times.
- [ ] **Step 2: Run** `timeout 1200 ./gradlew :androidApp:testDebugUnitTest --tests '*WidgetCoversTest*' -q`. Expected: FAIL (undefined).
- [ ] **Step 3: Implement** `WidgetCovers`, `downloadCover` and `generatedCoverBitmap`.
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit** "Widget: cover bitmaps (photo, cached web cover, generated)".

### Task 3: The stack of cards

**Files:**
- Modify: `androidApp/src/main/kotlin/com/mcosta/mybooks/widget/ReadingWidget.kt`
- Create: `androidApp/src/main/kotlin/com/mcosta/mybooks/widget/ReadingWidgetService.kt`
- Create: `androidApp/src/main/res/layout/widget_card.xml` and `androidApp/src/main/res/layout/widget_empty.xml`
- Modify: `androidApp/src/main/res/layout/widget_reading.xml`, `androidApp/src/main/res/xml/reading_widget_info.xml`, `androidApp/src/main/AndroidManifest.xml`
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/RoundFourAndroidTest.kt` (update the widget test), `androidApp/src/test/kotlin/com/mcosta/mybooks/widget/ReadingCardsTest.kt`, and screenshots in `ScreenshotTest`

**Interfaces:**
- Consumes: `ReadingSummary.reading` (Task 1) and `WidgetCovers.bitmapFor` (Task 2).
- **Produces:**
  - **`widget_reading.xml`** contains, in order: header `widget_label`; `StackView` `widget_stack` (height 0dp, weight 1, `loopViews="true"`) with empty view `widget_empty`, which is an `include` of `widget_empty.xml` holding the existing title/author texts; and the unchanged challenge block.
  - **`widget_card.xml`** (id `widget_card`) contains `ImageView` `card_cover` (about 72×108dp) and `card_title` (2 lines, serif), `card_author` (1 line), and `card_progress_row` holding `card_progress` and `card_percent`.
  - **`ReadingWidget.views(context, summary)`** sets the header text, the challenge, and `setRemoteAdapter(R.id.widget_stack, Intent(context, ReadingWidgetService::class.java))`.
    - It calls `setEmptyView(R.id.widget_stack, R.id.widget_empty)`.
    - The empty view's click opens To read, as today.
    - It sets `setPendingIntentTemplate(R.id.widget_stack, …)` for `MainActivity` with `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`, so the fill-in can add `OPEN_ROUTE`.
  - **`class ReadingWidgetService : RemoteViewsService()`** returns `ReadingCardsFactory(applicationContext, container)`.
  - **`class ReadingCardsFactory(context: Context, private val books: () -> List<Book>, private val covers: WidgetCovers) : RemoteViewsService.RemoteViewsFactory`**:
    - `onDataSetChanged` loads `books()`.
    - `getViewAt(i)` returns `cardFor(books[i])`.
    - `getItemId` returns the book id, and `hasStableIds` returns true.
    - The production `books` lambda is `runBlocking { container.readingSummaries.first().reading }`.
  - **`fun cardFor(context: Context, book: Book, cover: Bitmap): RemoteViews`** is internal. It fills a card and calls `setOnClickFillInIntent(R.id.widget_card, Intent().putExtra(MainActivity.OPEN_ROUTE, Routes.book(book.id)))`.
  - **`follow` and `show`** also call `manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_stack)`.
  - **Manifest:** `<service android:name=".widget.ReadingWidgetService" android:permission="android.permission.BIND_REMOTEVIEWS" android:exported="false" />`.

- [ ] **Step 1: Write failing tests.**
  - In `RoundFourAndroidTest`, update the widget test.
    - `ReadingSummary(listOf(book, other), ChallengeProgress(2026, 25, 5))` shows the header "Reading · 2 books" and the challenge text "2026 challenge: 5 of 25 books".
    - With one book, the header is "Reading".
  - In `ReadingCardsTest`, use a fake `books` lambda and a `WidgetCovers` with fake download.
    - `everyBookBeingReadGetsACardInOrder`: give it 4 books. After `onDataSetChanged()`, assert `getCount() == 4`. Assert that applying `getViewAt(i)` gives titles in input order, and `getItemId(i) == books[i].id`.
    - `aCardShowsTitleAuthorProgressAndOpensTheBook`: apply `cardFor`. Assert the title, the author and "26%". Assert the fill-in intent (read with `shadowOf`) carries `OPEN_ROUTE == Routes.book(id)`.
    - `aCardWithoutProgressHidesTheBar`: `progressPercent = null`, so `card_progress_row` visibility is `GONE`.
    - `nothingBeingReadShowsTheToReadCard`: `views(context, ReadingSummary(emptyList(), null))` has a `widget_empty` showing "Nothing on the go".
  - In `ScreenshotTest`, add `widgetCardLight` and `widgetCardDark`. Each one applies `cardFor` for a sample book with a generated cover into a 300dp-wide `FrameLayout`, then measures, lays out and draws it into a bitmap saved as `build/screenshots/widget-card-{light,dark}.png`.
- [ ] **Step 2: Run** `timeout 1200 ./gradlew :androidApp:testDebugUnitTest --tests '*ReadingCardsTest*' --tests '*RoundFourAndroidTest*' -q`. Expected: FAIL.
- [ ] **Step 3: Implement** the layouts, widget info, service, factory, `cardFor`, the new `views` and the refresh.
- [ ] **Step 4: Run** the full gate (both commands), then look at the two card screenshots. Expected: green, and the card reads well in light and dark.
- [ ] **Step 5: Commit** "Widget: a stack of covers for the books being read".
- [ ] **Step 6: On the phone (after a DB backup).** Install, then remove and re-add the widget (its size changed). Check four things: you can flick between the books being read, covers show, tapping a card opens that book, and the widget updates after a progress change in the app.
