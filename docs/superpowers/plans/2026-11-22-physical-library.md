# Physical Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a person model the bookcases in their home and record where each physical book sits, on Android, iPhone and the website.

**Architecture:** Each book gains `shelfId` and `shelfPosition`, synced as ordinary book fields. Each bookcase is one synced preference, `bookcase.<uuid>`, holding JSON. The same pure rules exist in Kotlin (`physical/`) and TypeScript (`web/src/physical/`), pinned to one stored format by an identical fixture.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Room KMP, kotlinx.serialization; React 18, TypeScript, TanStack Router, Vitest + RTL; Supabase Postgres, pgTAP.

**Spec:** `docs/superpowers/specs/2026-11-22-physical-library-design.md`

## Global Constraints

- **No new sync table.** Today's Android app fails a pull on an unknown table (`SupabaseSync.table()`).
- Backup stays `version: 1`; every new backup field is optional.
- Location is private: never add `shelf_id` or `shelf_position` to `publicState`, `publicFieldsDiffer` or any community call.
- Furniture changes never delete or otherwise alter a book, beyond clearing its two place fields.
- Limits: names 60 characters, 1 to 10 columns, 1 to 15 shelves per column, `shelf_id` 40 characters.
- `shelfId` and `shelfPosition` are always written together, with the same time.
- Shared Kotlin must compile for iOS: common code uses no JVM-only API (CI `ios` job builds tests too; `MutableList.replaceAll` already broke it once).
- User-facing names: **Physical library**, **Without a place**, **Where it is**, **Put on a shelf**, **Move**, **Add a bookcase**, **Scan**, **Pick from library**.
- **Commit, push, merge, production migration and phone installs happen only when the user asks.** Tasks end on green checks.
- Never print secrets; never read `local.properties` values into output.

**Commands**

- Shared tests: `./gradlew :shared:testAndroidHostTest --tests "*<Name>*" --console=plain`
- App tests: `./gradlew :androidApp:testDebugUnitTest --tests "*<Name>*" --console=plain`
- Web tests: `cd web && export PATH="/c/Users/mcosta/devtools/node-v18.20.8-win-x64:$PATH" && npm test -- --run <path>`
- Android gate: `./gradlew compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Web gate: `cd web && export PATH="/c/Users/mcosta/devtools/node-v18.20.8-win-x64:$PATH" && npm run lint && npm test -- --run && npm run build && npm run check:reproducible` (lint warnings are tolerated only in the git-excluded `src/preview.tsx`)

## Review Focus

1. A book changed by the edit form, a status change, a loan or an older app keeps its place. Pinned in Tasks 3 and 9.
2. A bookcase removed on one device while another still places books on it: those books read as **Without a place** and nothing crashes. Pinned in Tasks 2 and 10.
3. Reordering beside books that have no position, or with no gap left, renumbers that shelf and keeps the visible order. Pinned in Tasks 2 and 10.
4. Signing out and moving an account's books to the phone brings the bookcases, so books keep their place. Pinned in Task 4.
5. A whole-number position from the server (`3`, not `3.0`) is not treated as a local change and re-uploaded forever. Pinned in Task 3.

---

## File Map

**Create (Kotlin, `shared/src/commonMain/kotlin/com/mcosta/mybooks/`):** `physical/PhysicalLibrary.kt` (pure rules), `physical/BookcaseStore.kt` (synced storage), `sync/ShelfPair.kt`, `ui/physical/PhysicalLibraryViewModel.kt`, `PhysicalLibraryScreen.kt`, `BookcaseEditScreen.kt`, `BookcaseDrawing.kt`, `BookcaseScreen.kt`, `ShelfScreen.kt`, `PickBooksSheet.kt`, `WithoutPlaceScreen.kt`, `WhereItIsCard.kt`.

**Create (web, `web/src/physical/`):** `model.ts`, `model.test.ts`, `places.ts`, `useBookcases.ts`, `BookcaseDrawing.tsx`, `PhysicalLibraryPage.tsx`, `BookcaseEditPage.tsx`, `BookcasePage.tsx`, `ShelfPage.tsx`, `PickBooksDialog.tsx`, `WithoutPlacePage.tsx`, `WhereItIs.tsx`, `physical.test.tsx`.

**Create (database):** `supabase/migrations/2026112202_book_shelf_place.sql`, `supabase/tests/book_shelf_place_test.sql`.

---

### Task 1: Database columns

**Files:** Create the migration and pgTAP test above.

**Interfaces:** Produces `library_books.shelf_id text`, `library_books.shelf_position double precision`.

- [ ] **Step 1: Write the failing pgTAP test.** Follow `supabase/tests/my_shared_book_ids_test.sql`. As one user, `sync_push` a book with `shelf_id='s1'`, `shelf_position=2.5` and times. Assert:
  - `sync_pull(0)` returns those values for the book.
  - A push with an older time for `shelf_id` doesn't change it.
  - A `shelf_id` of 41 characters is set aside by `sync_push`, as any over-limit field is.
  - `publish_book` and `shared_books` carry no `shelf_id` column (`hasnt_column('shared_books', 'shelf_id')`).
- [ ] **Step 2: Write the migration.** `alter table library_books add column shelf_id text check (char_length(shelf_id) <= 40), add column shelf_position double precision;` Nothing else: `_merge_row` and `sync_pull` read columns dynamically.
- [ ] **Step 3: Verify locally** by running all migrations in a throwaway in-memory Postgres (PGlite) and the test with stand-ins. Real pgTAP runs in the PR's `db-tests` check.

Expected: every assertion passes.

### Task 2: Kotlin rules and the stored format

**Files:** Create `physical/PhysicalLibrary.kt`; Test `shared/src/commonTest/kotlin/com/mcosta/mybooks/physical/PhysicalLibraryTest.kt`.

**Interfaces (produces):**

```kotlin
const val BOOKCASE_PREFIX = "bookcase."
const val MAX_BOOKCASE_NAME = 60; const val MAX_COLUMNS = 10; const val MAX_SHELVES = 15
data class BookcaseColumn(val id: String, val shelves: List<String>)
data class Bookcase(val id: String, val name: String, val room: String, val columns: List<BookcaseColumn>, val readOnly: Boolean = false)
data class ShelfPlace(val bookcase: Bookcase, val column: Int, val shelf: Int, val shelfId: String) { val label: String; val location: String }
fun shelfLabel(column: Int, shelf: Int): String
fun newBookcase(name: String, room: String, columns: Int, shelves: Int, newId: () -> String = ::newUuid): Bookcase
fun Bookcase.toStored(): String
fun parseBookcase(key: String, value: String): Bookcase?
fun Bookcase.shelfIds(): List<String>
fun Bookcase.withShelfAdded(column: Int, newId: () -> String = ::newUuid): Bookcase
fun Bookcase.withoutShelf(shelfId: String): Bookcase
fun Bookcase.withColumnAdded(shelves: Int, newId: () -> String = ::newUuid): Bookcase
fun Bookcase.withoutColumn(column: Int): Bookcase
fun List<Bookcase>.findShelf(shelfId: String?): ShelfPlace?
fun List<Bookcase>.byRoom(): List<Pair<String, List<Bookcase>>>
fun List<Book>.onShelf(shelfId: String): List<Book>
fun List<Book>.withoutPlace(bookcases: List<Bookcase>, ebookIds: Set<Long>): List<Book>
fun endPosition(shelfBooks: List<Book>): Double
fun positionBetween(before: Double?, after: Double?): Double?
fun renumbered(shelfBooks: List<Book>): Map<Long, Double>
```

This task also adds `shelfId: String? = null` and `shelfPosition: Double? = null` to `domain/Book.kt`, because the rules read them. Task 3 carries them through storage.

- [ ] **Step 1: Write the failing tests:**
  - `theStoredFormatIsExact`: `Bookcase("x", "By the window", "Living room", [c1:[s1,s2], c2:[s3]]).toStored()` equals exactly `{"v":1,"name":"By the window","room":"Living room","columns":[{"id":"c1","shelves":["s1","s2"]},{"id":"c2","shelves":["s3"]}]}`, and parsing it with key `bookcase.x` gives the same bookcase.
  - `labels`: `shelfLabel(0,1) == "A2"`, `shelfLabel(2,0) == "C1"`; `location` is `Living room › By the window › Shelf A2`.
  - `newBookcaseClampsItsSize`: `newBookcase("  n ", " r ", 11, 16)` has 10 columns of 15 shelves, trimmed names; a 61-character name is cut to 60.
  - `eachColumnCanDiffer`: adding a shelf to column B leaves A alone; at 15 shelves it is a no-op; removing the last shelf of a column or the last column is a no-op.
  - `removingAShelfKeepsOtherIds`: labels renumber, ids don't.
  - `unreadableValuesAreSkipped`: `parseBookcase("bookcase.x", "nope")`, `""`, and a value with no columns return null.
  - `aNewerFormatIsReadOnly`: `"v":2` with readable columns parses with `readOnly = true`, and its extra keys are ignored.
  - `blankRoomReadsAsNoRoom`: an empty stored room reads as `"No room"`.
  - `roomsAndBookcasesSortByName`: `byRoom` sorts rooms by name, and bookcases by name within a room, ignoring case.
  - `shelfOrder`: `onShelf` sorts by position, nulls last, then title, and leaves out WISHLIST.
  - `withoutPlaceRules`: includes no shelf and an orphaned shelf id; excludes WISHLIST, books in `ebookIds`, and books on a real shelf.
  - `endPosition`: empty gives `1.0`; highest `3.5` gives `4.5`.
  - `positionBetween`: `(null,null)=1.0`, `(null,2.0)=1.0`, `(2.0,null)=3.0`, `(1.0,2.0)=1.5`, `(1.0, 1.0000001)=null`.
  - `renumberedKeepsTheVisibleOrder`: positions become `1.0, 2.0, ...` in `onShelf` order, including books that had null.
- [ ] **Step 2: Run them.** Expected: compile failure on `PhysicalLibrary`.
- [ ] **Step 3: Implement.** Use `kotlinx.serialization` with `ignoreUnknownKeys`, and field order `v, name, room, columns`. `positionBetween` returns null when `after - before < 1e-6`. `parseBookcase` strips `BOOKCASE_PREFIX` for the id.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 3: Android book fields through storage, sync and backup

**Files:**
- Modify: `data/db/BookEntity.kt`, `data/db/AppDatabase.kt`, `sync/SyncCodec.kt`, `sync/FieldTimes.kt`, `sync/SyncingDaos.kt`, `data/BookRepository.kt` (`withEdits`), `data/backup/BackupSerializer.kt`, `sync/FirstMerge.kt`, `library/LibraryTransfer.kt`
- Create: `sync/ShelfPair.kt`
- Generated: `shared/schemas/com.mcosta.mybooks.data.db.AppDatabase/9.json`
- Test: `shared/src/commonTest/.../physical/BookPlaceSyncTest.kt`, `androidApp/src/test/kotlin/com/mcosta/mybooks/PlaceMigrationTest.kt`

**Interfaces (produces):**

```kotlin
val MIGRATION_8_9: Migration
val SHELF_FIELDS: Set<String> // "shelf_id", "shelf_position"
fun stamp(old, new, oldTimes, now, together: Set<String> = emptySet()): Map<String, Long>
fun SyncRow.withShelfPair(kept: SyncRow, other: SyncRow, isValid: (String) -> Boolean = { true }): SyncRow
```

- [ ] **Step 1: Write the failing tests:**
  - `bookFieldsRoundTrip`: `Book → entity → row → entity → Book` keeps `shelfId` and `shelfPosition`, with wire names `shelf_id`, `shelf_position`.
  - `changingEitherStampsBoth`: `stamp(..., together = SHELF_FIELDS)` gives both the same new time when only the position changed.
  - `aWholeNumberFromTheServerIsNotAChange`: a pulled row with JSON `3` decodes to `3.0`; saving that entity unchanged queues nothing.
  - `theEditFormKeepsThePlace`: `save(edited, base)` with a place set on the stored book after `base` was read keeps it.
  - `statusAndLoanKeepThePlace`: `setStatus`, `lend`, `returned` leave both fields.
  - `backupRoundTrip`: `encode` then `decode` keeps both; a backup without them decodes to null.
  - `shelfPairFollowsThePlacedBook`: with `kept` unplaced and `other` on `s1` at `2.0`, the result has `s1`, `2.0`, never a mix.
  - `shelfPairPrefersTheKeptBook`: both placed keeps `kept`'s pair.
  - `shelfPairIgnoresAnInvalidShelf`: `kept` on a missing shelf and `other` on a valid one takes `other`.
  - `olderRowsWithoutTheFieldsStayIntact`: `mergeFields` of a row with no shelf keys keeps the local pair.
- [ ] **Step 2: Write `PlaceMigrationTest.placeColumnsArriveAndBooksStay`.** Robolectric: build a real version 8 file from `8.json` (`createSql` of entities and indices with `${TABLE_NAME}` replaced, its `setupQueries`, `PRAGMA user_version = 8`), insert one book, open with `createAndroidDatabase`, assert the title survives and `shelfId == null`.
- [ ] **Step 3: Run them.** Expected: compile or assertion failures.
- [ ] **Step 4: Implement.** Bump Room to 9; `MIGRATION_8_9` runs `ALTER TABLE books ADD COLUMN shelfId TEXT` and `ALTER TABLE books ADD COLUMN shelfPosition REAL`; register it in `addMigrations`. Pass `together = SHELF_FIELDS` only from `BookEntity.stampedFrom`. Add a `double` accessor in `SyncCodec`. In `withEdits`, pick the two fields as one unit. Apply `withShelfPair` in `FirstMerge.combine` and `LibraryTransfer.combine`.
- [ ] **Step 5: Run the tests, then the full shared suite.** Expected: PASS and `9.json` generated.

### Task 4: Android bookcase storage

**Files:**
- Create: `physical/BookcaseStore.kt`
- Modify: `sync/LibraryPrefs.kt`, `library/LibrarySpace.kt`, `library/LibraryTransfer.kt`, `data/BookRepository.kt` (export, import), `data/backup/BackupSerializer.kt`, `AppContainer.kt`
- Test: `shared/src/commonTest/.../physical/BookcaseStoreTest.kt`; extend `library/LibraryTransferTest.kt`

**Interfaces (produces):**

```kotlin
class BookcaseStore(
    prefs: Preferences,
    storedKeys: () -> Collection<String>,
    onChanged: suspend (String) -> Unit,
    persist: suspend (key: String, value: String) -> Unit = { _, _ -> },
) {
    val bookcases: StateFlow<List<Bookcase>>
    val keys: List<String>                 // removed ones stay listed, so the removal syncs
    fun value(key: String): String?        // null when removed
    fun reload()
    fun pulled(key: String, value: String?)
    suspend fun save(bookcase: Bookcase)
    suspend fun remove(id: String)
    suspend fun restore(id: String, value: String)      // replaces
    suspend fun adoptIfMissing(id: String, value: String)
    fun all(): List<Pair<String, String>>  // id, stored JSON
}
// AppContainer: val bookcases: BookcaseStore get() = space.bookcases
// LibraryContents gains: val bookcases: BookcaseStore?
// BackupSerializer.encode(..., bookcases: List<Pair<String, String>> = emptyList()); decodeBookcases(text)
```

- [ ] **Step 1: Write the failing tests:**
  - `saveAndRemoveSync`: `save` writes `bookcase.<id>` and calls `onChanged`; `remove` leaves the key in `keys`, `value` null, and the bookcase gone from the flow.
  - `pulledValuesShowWithoutReportingAChange`: `pulled` updates the flow and does not call `onChanged`.
  - `anOlderAppIgnoresTheKey`: a `LibraryPrefs` built without a store returns null for `bookcase.x` and doesn't throw on `set`.
  - `libraryPrefsListsBookcases`: with a store, `keys`, `get` and `set` include `bookcase.*`.
  - `backupCarriesBookcases`: `exportJson` then `importJson` restores one; same id is replaced; a backup without the array leaves the layout alone.
  - `movingToThePhoneBringsTheBookcases` (in `LibraryTransferTest`): after `transfer.move`, the target has the bookcase and the moved book's shelf resolves.
  - `movingKeepsATargetBookcaseWithTheSameId`: `adoptIfMissing` doesn't overwrite.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement,** following `ShareExclusions`: removal stores `""`, and `value` maps it to null. Build one store in `LibrarySpace` from `libraryPrefs`, `prefChanged` and `storeLibraryPreference`; reload it in `open()`; pass it to `LibraryPrefs`, `BookRepository` and `LibraryContents`. Call `adoptIfMissing` for each source bookcase in `LibraryTransfer.move`, before the books. Pass the bookcases to `encode` from both `exportBackup` and `exportJson`.
- [ ] **Step 4: Run them, then the full shared suite.** Expected: PASS.

### Task 5: Android place operations and view model

**Files:**
- Modify: `data/BookRepository.kt`, `data/db/EbookEntity.kt` (`EbookDao` flow if none exists)
- Create: `ui/physical/PhysicalLibraryViewModel.kt`
- Test: `shared/src/commonTest/.../ui/physical/PhysicalLibraryViewModelTest.kt`

**Interfaces (produces):**

```kotlin
// BookRepository
suspend fun setPlace(id: Long, shelfId: String?, position: Double?)
suspend fun placeAtEnd(id: Long, shelfId: String)
suspend fun moveOnShelf(id: Long, shelfId: String, beforeId: Long?, afterId: Long?)
suspend fun clearPlaces(shelfIds: Set<String>): Int
fun observeEbookBookIds(): Flow<Set<Long>>

data class PhysicalState(
    val bookcases: List<Bookcase> = emptyList(),
    val rooms: List<Pair<String, List<Bookcase>>> = emptyList(),
    val books: List<Book> = emptyList(),
    val withoutPlace: List<Book> = emptyList(),
    val message: String? = null,
    val undo: (() -> Unit)? = null,
)
class PhysicalLibraryViewModel(repo: BookRepository, store: BookcaseStore) : ViewModel() {
    val state: StateFlow<PhysicalState>
    fun booksOn(shelfId: String): List<Book>
    fun countOn(shelfIds: Collection<String>): Int
    fun saveBookcase(bookcase: Bookcase)
    fun renameRoom(from: String, to: String)
    fun removeBookcase(id: String)
    fun removeShelf(bookcase: Bookcase, shelfId: String)
    fun removeColumn(bookcase: Bookcase, column: Int)
    fun place(bookIds: List<Long>, shelfId: String)    // in order, at the end, with Undo
    fun takeOff(bookId: Long)
    fun moveBook(bookId: Long, shelfId: String, toIndex: Int)
    fun dismissMessage()
}
```

- [ ] **Step 1: Write the failing tests:**
  - `placeAppendsInOrder`: placing `[b, a]` on an empty shelf gives positions `1.0, 2.0`.
  - `undoPutsBooksBack`: undo restores each book's previous shelf and position, including none.
  - `movingToTheMiddleChangesOnlyThatBook`: `[1,2,3]` moving the third to index 1 changes one row, to `1.5`.
  - `movingBesideUnorderedBooksRenumbers`: a shelf with null positions renumbers and shows the requested order.
  - `noGapLeftRenumbers`: positions `1.0, 1.0000001` still accept a book between them.
  - `removingABookcaseFreesItsBooks`: books go to `withoutPlace`, keep their title and status, and fields are null.
  - `removingAShelfFreesOnlyItsBooks`.
  - `aBookOnARemovedShelfReadsAsWithoutAPlace`: a synced removal with fields left in place.
  - `countOnSaysHowManyAreAffected`.
  - `renamingARoomUpdatesItsBookcases`: every bookcase in that room changes, others don't, and read-only ones are skipped.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Combine `observeBooks(null, "")`, `store.bookcases` and `observeEbookBookIds()`. Run multi-book writes in `transactionRunner`. `moveOnShelf` uses `positionBetween`, and calls `renumbered` when it returns null or a neighbour has no position.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 6: Android home, editor and navigation

**Files:**
- Create: `ui/physical/PhysicalLibraryScreen.kt`, `BookcaseEditScreen.kt`, `BookcaseDrawing.kt`
- Modify: `ui/settings/MoreScreen.kt`, `ui/MyBooksNavHost.kt`
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/PhysicalLibraryUiTest.kt`; update `HomeAndMoreUiTest`

**Interfaces (produces):**

```kotlin
// Routes
const val PHYSICAL = "physical"
const val BOOKCASE = "physical/case/{id}"
const val BOOKCASE_EDIT = "physical/edit?id={id}"
const val PHYSICAL_SHELF = "physical/shelf/{id}"
const val UNPLACED = "physical/unplaced"
fun bookcase(id: String): String; fun bookcaseEdit(id: String? = null): String; fun physicalShelf(id: String): String

@Composable fun BookcaseDrawing(bookcase: Bookcase, booksOn: (String) -> List<Book>, modifier: Modifier = Modifier,
    labels: Boolean = false, shelfHeight: Dp = 56.dp, highlightShelf: String? = null, highlightBook: Long? = null, onShelf: ((String) -> Unit)? = null)
@Composable fun PhysicalLibraryScreen(viewModel, onBack, onOpenBookcase: (String) -> Unit, onAdd: () -> Unit, onOpenUnplaced: () -> Unit)
@Composable fun BookcaseEditScreen(viewModel, bookcaseId: String?, onDone: () -> Unit)
fun MoreScreen(onOpenPhysical: () -> Unit, onOpenNews: () -> Unit, onOpenSettings: () -> Unit)
```

- [ ] **Step 1: Write the failing UI tests:**
  - `moreOpensPhysicalLibrary`: the **Physical library** row calls its callback, first in the list.
  - `emptyHomeInvitesABookcase`: shows **Add a bookcase**.
  - `roomsListTheirBookcases`: room heading, name, `8 shelves · 86 books`, and `37 books without a place`.
  - `editorDrawsWhatItWillSave`: set name and room, Columns 2, Shelves 4, Save; the store holds 2 columns of 4.
  - `saveNeedsANameAndARoom`: Save is disabled with either blank.
  - `editingChangesOneColumn`: **Add a shelf** in column B gives 4 and 5.
  - `removingAShelfWithBooksAsksFirst`: the dialog says `2 books will go to Without a place`.
  - `aReadOnlyBookcaseCantBeEdited`: no Save action.
  - `aRoomCanBeRenamed`: **Rename room** on the room heading updates its bookcases.
  - `aFullShelfShowsHowManyMore`: a shelf with more spines than fit shows `+N`.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Spines use `coverColorArgb(title)` and are not individually clickable. Each shelf is a click target of at least 48dp with a content description such as `Shelf A2, 14 books`. A lent spine is drawn dimmed. Register the routes with `screen(...)`; share one view model across them.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 7: Android bookcase, shelf, pick and Without a place

**Files:**
- Create: `ui/physical/BookcaseScreen.kt`, `ShelfScreen.kt`, `PickBooksSheet.kt`, `WithoutPlaceScreen.kt`
- Modify: `ui/MyBooksNavHost.kt`
- Test: extend `PhysicalLibraryUiTest.kt`

**Interfaces (produces):**

```kotlin
@Composable fun BookcaseScreen(viewModel, bookcaseId: String, onBack, onEdit: () -> Unit, onOpenShelf: (String) -> Unit)
@Composable fun ShelfScreen(viewModel, shelfId: String, onBack, onOpenBook: (Long) -> Unit, onScan: (() -> Unit)?)
@Composable fun PickBooksSheet(books: List<Book>, bookcases: List<Bookcase>, ebookIds: Set<Long>, onPick: (List<Long>) -> Unit, onDismiss: () -> Unit)
@Composable fun WithoutPlaceScreen(viewModel, onBack, onOpenBook: (Long) -> Unit)
```

- [ ] **Step 1: Write the failing UI tests:**
  - `tappingAShelfOpensIt`.
  - `aShelfListsBooksInOrder` with title and author, and a lent book marked `Lent to Ana`.
  - `moveDownChangesTheOrder`: the accessible **Move down** action moves a book.
  - `takingABookOffSendsItToWithoutAPlace`.
  - `pickSearchesAndPlaces`: filter by title, tick two, confirm; both are on the shelf and Undo is offered.
  - `pickShowsWhereABookIs`: a placed book shows its location.
  - `pickHidesToBuyAndEbooks`.
  - `withoutPlaceCanPlaceSeveral`: tick two, choose a shelf.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Reorder uses `detectDragGesturesAfterLongPress` on a handle plus **Move up** / **Move down** custom accessibility actions, calling `moveBook` once on drop. No new dependency. `onScan` is non-null only where `LocalPlatformServices.current` has a scanner.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 8: Android scan onto a shelf

**Files:**
- Modify: `ui/scan/BatchScanViewModel.kt`, `ui/scan/BatchScanScreen.kt`, `ui/MyBooksNavHost.kt`, `ui/edit/EditBookViewModel.kt`
- Test: `shared/src/commonTest/.../ui/scan/ShelfScanTest.kt`

**Interfaces (produces):**

```kotlin
class BatchScanViewModel(private val repo: BookRepository, private val shelfId: String? = null)
// ScanResult gains:
data class Placed(val book: Book, val previousShelf: String?, val previousPosition: Double?, val previousStatus: ReadingStatus) : ScanResult
data class AlreadyOnShelf(val book: Book) : ScanResult
// Routes
const val SHELF_SCAN = "physical/shelf/{id}/scan"; fun shelfScan(id: String): String
fun add(isbn: String? = null, shelf: String? = null): String
class EditBookViewModel(repo, bookId, isbn, private val placeOnShelf: String? = null)
```

- [ ] **Step 1: Write the failing tests:**
  - `scanningBuildsTheOrder`: three ISBNs in a row get positions `1.0, 2.0, 3.0`.
  - `aBookFromAnotherShelfMoves`, and `undo` puts it back on that shelf at its position.
  - `alreadyOnThisShelfStaysPut`: result `AlreadyOnShelf`, position unchanged.
  - `aNewBookIsAddedAsToReadAndPlaced`, and `undo` deletes it.
  - `aToBuyBookBecomesToReadWhenScanned`, and `undo` restores To buy and no place.
  - `notFoundOffersAddingByHand`: stays `NotFound`, with nothing placed.
  - `addedByHandLandsOnTheShelf`: `EditBookViewModel(..., placeOnShelf = "s1").save()` places at the end.
  - `withoutAShelfScanningIsUnchanged`: the existing batch behaviour.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** With a `shelfId`, the status selector is hidden. `Routes.add` keeps its current URLs when `shelf` is null.
- [ ] **Step 4: Run them, plus existing scan tests.** Expected: PASS.

### Task 9: Android book page

**Files:**
- Create: `ui/physical/WhereItIsCard.kt`
- Modify: `ui/detail/BookDetailScreen.kt`, `ui/MyBooksNavHost.kt`
- Test: extend `PhysicalLibraryUiTest.kt`

**Interfaces (produces):**

```kotlin
@Composable fun WhereItIsCard(book: Book, bookcases: List<Bookcase>, booksOn: (String) -> List<Book>,
    onChoose: (shelfId: String) -> Unit, onTakeOff: () -> Unit, onOpenShelf: (String) -> Unit)
// BookDetailScreen gains: physical: PhysicalLibraryViewModel? = null
```

- [ ] **Step 1: Write the failing UI tests:**
  - `showsTheFullLocation`: `Living room › By the window › Shelf A2` and `4th from the left`.
  - `withoutAPlaceOffersPuttingItOnAShelf`: **Put on a shelf** opens a room, bookcase and shelf choice.
  - `moveChangesTheShelf`.
  - `hiddenForToBuy`.
  - `anEbookCanStillBePlaced`.
  - `withoutBookcasesItLeadsToCreatingOne`.
  - `lentShowsBoth`: loan line and location.
  - `aBookEditedKeepsItsLocation` through the real edit screen flow.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Place the card directly after the status selector. Ordinal text: `1st`, `2nd`, `3rd`, `4th`, `11th`, `21st`, `22nd`, `23rd`. Show the ordinal only when the book has a position.
- [ ] **Step 4: Run the Android gate.** Expected: BUILD SUCCESSFUL.

### Task 10: Website rules, fields, backup and merge

**Files:**
- Create: `web/src/physical/model.ts`, `model.test.ts`, `places.ts`
- Modify: `web/src/sync/model.ts`, `sync/codec.ts`, `sync/limits.ts`, `sync/store.ts`, `sync/twin.ts`, `add/addBook.ts`, `io/backup.ts`, `io/goodreads.ts`, `io/importPlan.ts`, `io/exports.ts`, `tidy/mergeBooks.ts`
- Test: `model.test.ts`, plus `io/backup.test.ts`, `tidy` and `sync` suites

**Interfaces (produces):**

```ts
// model.ts: the same rules and names as Task 2, with Book ids as strings
export type BookcaseColumn = { id: string; shelves: string[] }
export type Bookcase = { id: string; name: string; room: string; columns: BookcaseColumn[]; readOnly: boolean }
export type ShelfPlace = { bookcase: Bookcase; column: number; shelf: number; shelfId: string; label: string; location: string }
export const BOOKCASE_PREFIX = 'bookcase.'
export function shelfLabel(column: number, shelf: number): string
export function newBookcase(name: string, room: string, columns: number, shelves: number, newId?: () => string): Bookcase
export function toStored(b: Bookcase): string
export function parseBookcase(key: string, value: string): Bookcase | null
export function shelfIds(b: Bookcase): string[]
export function withShelfAdded(b: Bookcase, column: number, newId?: () => string): Bookcase
export function withoutShelf(b: Bookcase, shelfId: string): Bookcase
export function withColumnAdded(b: Bookcase, shelves: number, newId?: () => string): Bookcase
export function withoutColumn(b: Bookcase, column: number): Bookcase
export function findShelf(cases: Bookcase[], shelfId: string | null): ShelfPlace | null
export function byRoom(cases: Bookcase[]): [string, Bookcase[]][]
export function onShelf(books: Book[], shelfId: string): Book[]
export function withoutPlace(books: Book[], cases: Bookcase[], ebookIds: ReadonlySet<string>): Book[]
export function endPosition(shelfBooks: Book[]): number
export function positionBetween(before: number | null, after: number | null): number | null
export function renumbered(shelfBooks: Book[]): Map<string, number>

// places.ts: writes both fields, restamped together
export function setPlace(store: LibraryStore, id: string, shelfId: string | null, position: number | null): void
export function placeAtEnd(store: LibraryStore, id: string, shelfId: string): void
export function moveOnShelf(store: LibraryStore, id: string, shelfId: string, toIndex: number): void
export function clearPlaces(store: LibraryStore, shelfIds: ReadonlySet<string>): number

// store.ts
prefKeys(prefix: string): string[]
ebookIds(): Set<string>
// Book gains shelfId: string | null; shelfPosition: number | null
// Backup and BackupData gain bookcases: { id: string; value: string }[]
// sync/twin.ts
export function withShelfPair(merged: SyncRow, kept: SyncRow, other: SyncRow, isValid?: (id: string) => boolean): SyncRow
```

- [ ] **Step 1: Write the failing tests:** port every Task 2 test to `model.test.ts`, with the **identical** stored-format string. Add:
  - `codecCarriesThePlace` with wire names `shelf_id`, `shelf_position`.
  - `publicStateHasNoPlace`: `publicState` has no shelf key, and `publicFieldsDiffer` is false when only the place differs.
  - `setPlaceStampsBothFields`: both times change even if one value didn't.
  - `moveOnShelf` middle, unordered and no-gap cases.
  - `clearPlacesKeepsEverythingElse`.
  - `backupRoundTrip`: bookcases and fields survive; older files have none; a same-id bookcase is replaced on import.
  - `aBackupFromAndroidReads`: a literal file holding `bookcases` and `shelfId`.
  - `tidyMergeKeepsThePair`: placed and unplaced twins give the placed pair; both placed gives `keep`'s; an invalid shelf loses.
  - `importedPairIsNotMixed`.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Add `shelf_id: 40` to `BOOK_LIMITS`. In `combineRows`, finish with `withShelfPair`; `planMerge` passes an `isValid` built from the current bookcases. In `store.edit` callers, use `restamp: ['shelf_id', 'shelf_position']`.
- [ ] **Step 4: Run the web test suite.** Expected: PASS.

### Task 11: Website home, editor and navigation

**Files:**
- Create: `web/src/physical/useBookcases.ts`, `BookcaseDrawing.tsx`, `PhysicalLibraryPage.tsx`, `BookcaseEditPage.tsx`, `physical.test.tsx`
- Modify: `web/src/App.tsx`, `shell/BottomBar.tsx`, `theme.css`

**Interfaces (produces):**

```ts
export function useBookcases(store: LibraryStore): {
  bookcases: Bookcase[]
  save(b: Bookcase): void
  renameRoom(from: string, to: string): void
  remove(id: string): void    // clears its books' places first
}
export function BookcaseDrawing(props: {
  bookcase: Bookcase; books: Book[]; labels?: boolean; highlightShelf?: string | null;
  highlightBook?: string | null; onShelf?: (shelfId: string) => void; size?: 'mini' | 'full'
}): JSX.Element
// Routes: /physical, /physical/new, /physical/$id, /physical/$id/edit, /physical/shelf/$shelfId, /physical/unplaced
```

- [ ] **Step 1: Write the failing tests,** the same cases as Task 6, plus:
  - `moreListsPhysicalLibrary` in the phone sheet, and `/physical` is added to `MORE_PATHS`.
  - `aFullShelfShowsHowManyMore`.
  - `removingAShelfWithBooksAsksFirst` using `ConfirmDialog`.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Routes lazy-load like the other secondary pages and use `requireSession`. Spines use `coverColorCss(title)`. Each shelf is a `button` with `aria-label` such as `Shelf A2, 14 books`, at least 44px tall on a phone. Add **Physical library** to the desktop nav and the More sheet.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 12: Website bookcase, shelf, pick and Without a place

**Files:**
- Create: `web/src/physical/BookcasePage.tsx`, `ShelfPage.tsx`, `PickBooksDialog.tsx`, `WithoutPlacePage.tsx`
- Modify: `web/src/App.tsx`, `theme.css`; extend `physical.test.tsx`

**Interfaces (produces):**

```ts
export function PickBooksDialog(props: {
  store: LibraryStore; bookcases: Bookcase[]; onPick: (ids: string[]) => void; onClose: () => void
}): JSX.Element
```

- [ ] **Step 1: Write the failing tests,** the same cases as Task 7, plus `noScanButtonOnTheWebsite`.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Use the existing `Dialog` and toast with an **Undo** action. Reorder uses pointer events on a handle (which work on iPhone, unlike HTML5 drag) plus **Move up** / **Move down** buttons, and calls `moveOnShelf` once on drop.
- [ ] **Step 4: Run them.** Expected: PASS.

### Task 13: Website book page

**Files:**
- Create: `web/src/physical/WhereItIs.tsx`
- Modify: `web/src/book/AtAGlance.tsx`, `book/BookPage.tsx`, `theme.css`; extend `physical.test.tsx` and `book/BookPage.test.tsx`

**Interfaces (produces):** `export function WhereItIs({ store, book }: { store: LibraryStore; book: Book }): JSX.Element | null`; `AtAGlance` gains `whereItIs?: ReactNode`, rendered right after `{suggest}`.

- [ ] **Step 1: Write the failing tests,** the same cases as Task 9, plus `atAGlanceKeepsItsOrder`: dates, Suggest, Where it is, I'm on page, tags.
- [ ] **Step 2: Run them.** Expected: failure.
- [ ] **Step 3: Implement.** Any dialog opens through `createPortal` to `document.body`, because `.glance` scrolls and clips.
- [ ] **Step 4: Run the web gate.** Expected: all pass, and lint warnings only in `preview.tsx`.

### Task 14: Docs and final verification

**Files:** Modify `docs/privacy.html`, `docs/delete-account.html`, `README.md`.

- [ ] **Step 1: Update the docs.** Privacy: the saved library includes the bookcases you create and where each book is, private to your account and never shared. Delete-account: the cloud library's list includes bookcases. README: a **Physical library** feature entry.
- [ ] **Step 2: Run the Android gate and the web gate.** Expected: both green.
- [ ] **Step 3: Check the real layouts** at iPhone 15 and 1280px with sample data: no sideways page scroll, shelf targets at least 44px, inputs at least 16px, uneven columns, a long name, a full shelf.
- [ ] **Step 4: Run an older-client check in local Postgres:** a row with `bookcase.x` and shelf columns pulled by the previous web API bundle causes no error, and an edit from it leaves `shelf_id` unchanged.
- [ ] **Step 5: Stop and report to the user.** Recommend an independent whole-branch review. Then, each only on the user's OK: apply the migration to production and align its version in `schema_migrations` to `2026112202`; commit, PR and merge after CI; back up each phone's databases and install.
