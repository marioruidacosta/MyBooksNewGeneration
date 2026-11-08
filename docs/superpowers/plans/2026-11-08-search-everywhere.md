# Search Everywhere Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Search tab finds books in the library first and, when none match, searches New in Portugal and public catalogues (Google Books, Open Library) automatically; ISBNs work in all three.

**Architecture:** Pure matching functions (ISBN classification, New in Portugal match) and small HTTP sources (Google Books / Open Library text search) feed a `PublicBookSearch` and an `OutsideSearchViewModel` that debounces, cancels and keeps one state per section. `LibraryScreen` shows the sections under its empty list only on the Search route; results open the existing New in Portugal and catalogue-book pages.

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplatform, Ktor (MockEngine via `FakeHttpServer` in tests), kotlinx.serialization, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-08-search-everywhere-design.md`

## Global Constraints

- Outside search runs only on the Search route, only when the library has no match, after a 0.8 s pause (`OUTSIDE_SEARCH_DELAY_MS = 800L`), for queries of at least 3 characters (`OUTSIDE_SEARCH_MIN = 3`) or a valid ISBN.
- At most 20 results per section (`OUTSIDE_SEARCH_LIMIT = 20`).
- ISBN: valid ISBN-10 or ISBN-13, hyphens and spaces allowed, normalized with the existing `Isbn.normalize` to ISBN-13.
- Copy, exactly: section titles "New in Portugal", "Public sources"; "Searching…"; "Nothing in New in Portugal"; "Nothing found online"; "Couldn't search New in Portugal"; "Couldn't search online"; "Retry"; "Not a valid ISBN — check the digits"; "Not here?" + button "Add it yourself".
- Google Books: `/books/v1/volumes?q=<query>&printType=books&maxResults=20`, plus `&key=<key>` when `googleBooksApiKey` is set; the query URL-encoded.
- Open Library: `/search.json?q=<query>&limit=20&fields=title,author_name,first_publish_year,isbn,cover_i,language,publisher`; cover `https://covers.openlibrary.org/b/id/<cover_i>-M.jpg`.
- Common code: no `RegexOption.DOT_MATCHES_ALL`, no `FileSystem.SYSTEM`.
- Gate: `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`, then `./gradlew :androidReader:lintDebug :androidApp:lintDebug -q` as a separate run.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Typing fast: results of a superseded query must never replace a newer query's (Task 5 test `staleResultsAreDropped`).
2. Coming back to Search from a result page recomposes with the same query: it must not search again or flash "Searching…" (Task 5 test `sameQueryDoesNotSearchAgain`).
3. Queries with `&`, quotes, accents or `#` must reach Google Books and Open Library URL-encoded (Task 3 test `queryIsEncoded`).
4. No network and no saved New in Portugal months: that section shows "Couldn't search New in Portugal" with Retry, and Retry works once the network is back (Task 5 test `releasesFailThenRetry`).
5. Google Books answering 429 while Open Library works: Open Library's results still show (Task 4 test `oneSourceFailingKeepsTheOther`).

---

### Task 1: ISBN queries, and the library found by ISBN

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/domain/Isbn.kt`
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/BookRepository.kt` (`observeBooks`)
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/domain/IsbnQueryTest.kt` (create), `shared/src/commonTest/kotlin/com/mcosta/mybooks/data/BookRepositoryTest.kt`

**Interfaces:**
- Produces: `sealed interface IsbnQuery { data class Valid(val isbn13: String); data object Invalid; data object NotIsbn }` and `fun isbnQuery(raw: String): IsbnQuery` in `Isbn.kt`. `Invalid` = after removing hyphens and spaces, 10 characters (digits, last may be X) or 13 digits, but `Isbn.normalize` rejects it.
- Produces (in place of the spec's "LibraryViewModel uses findByIsbn": same result, and reactive): `BookRepository.observeBooks(status, query)` returns the books whose `isbn` equals the normalized ISBN when `isbnQuery(query)` is `Valid` (title/author filter otherwise). Shelves get this too.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun isbnQueries() {
    assertEquals(IsbnQuery.Valid("9789722036504"), isbnQuery("978-972-20-3650-4"))
    assertEquals(IsbnQuery.Valid("9789722036504"), isbnQuery(" 972-20-3650-X "))   // ISBN-10
    assertEquals(IsbnQuery.Invalid, isbnQuery("9789722036505"))                      // wrong check digit
    assertEquals(IsbnQuery.NotIsbn, isbnQuery("Saramago"))
    assertEquals(IsbnQuery.NotIsbn, isbnQuery("1984"))
}
// BookRepositoryTest
@Test fun searchFindsABookByIsbn() = runTest {
    repo.save(Book(title = "A Cidade e as Serras", authors = "Eça de Queirós", isbn = "9789722036504"))
    repo.save(Book(title = "Os Maias", authors = "Eça de Queirós"))
    assertEquals(listOf("A Cidade e as Serras"), repo.observeBooks(null, "972-20-3650-X").first().map { it.title })
}
```


- [ ] **Step 2: Run, expect FAIL** — `./gradlew :shared:testAndroidHostTest --tests '*IsbnQueryTest*' --tests '*BookRepositoryTest*'`: unresolved `isbnQuery`.
- [ ] **Step 3: Implement** `isbnQuery` and the ISBN branch in `observeBooks` (observe the status's books with an empty query and filter by `isbn`).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Search: ISBN queries; the library is searched by ISBN".

### Task 2: New in Portugal matched by title, author or ISBN

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/releases/ReleaseSearch.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/data/releases/ReleaseSearchTest.kt`

**Interfaces:**
- Consumes: `AllMonths`, `MonthBooks`, `ReleaseBook` (existing); `IsbnQuery` (Task 1); `searchKey` (existing).
- Produces: `data class ReleaseHit(val month: String, val book: ReleaseBook)` and `fun matchReleases(all: AllMonths, query: String, limit: Int = OUTSIDE_SEARCH_LIMIT): List<ReleaseHit>`; `const val OUTSIDE_SEARCH_LIMIT = 20` lives here.

- [ ] **Step 1: Write the failing tests** — `byTitleOrAuthorIgnoringAccentsAndCase` ("cegueira" finds "Ensaio sobre a Cegueira"; "saramago" finds a book by "José Saramago"; "JOSE" finds it too); `byIsbn` ("978-972-20-3650-4" finds only that book); `newestMonthFirstAndAtMost20` (25 matches over months "2026-10" and "2026-11": the first 20 returned, all "2026-11" ones before "2026-10"; `AllMonths.months` is already newest first); `aBookListedInTwoMonthsCountsOnce` (same `id` in two months: one hit, the newer month).
- [ ] **Step 2: Run, expect FAIL** (unresolved `matchReleases`).
- [ ] **Step 3: Implement** — keys computed per call with `searchKey`; title or any author contains the query key; for `IsbnQuery.Valid` compare `book.isbn`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "New in Portugal: match books by title, author or ISBN".

### Task 3: Google Books and Open Library text search

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/lookup/BookTextSearch.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/data/lookup/BookTextSearchTest.kt`

**Interfaces:**
- Consumes: `HttpClient.getOrNull`, `httpsUrl`, `yearFrom` (lookup/Http.kt); `AuthorBook` (authors/GoogleBooksAuthorSearch.kt); `Isbn.normalize`.
- Produces: `data class PublicHit(val book: AuthorBook, val portuguese: Boolean)`; `fun interface BookTextSource { suspend fun search(query: String): List<PublicHit> }`; `class GoogleBooksTextSearch(http: HttpClient, apiKey: String? = null, baseUrl: String = "https://www.googleapis.com") : BookTextSource`; `class OpenLibraryTextSearch(http: HttpClient, baseUrl: String = "https://openlibrary.org") : BookTextSource`. Both throw on HTTP or parse failure (callers decide); a 404 is an empty list.

- [ ] **Step 1: Write the failing tests** (inline JSON with `FakeResponse().setBody(...)`, `FakeHttpServer`):
  - `googleResultsMapped`: one volume → `AuthorBook(title, authors joined ", ", year from publishedDate, publisher, isbn = ISBN_13, coverUrl https, description, pageCount)`, `portuguese = (language == "pt")`; volumes without a title are skipped.
  - `openLibraryResultsMapped`: one doc → title, `author_name` joined ", ", `first_publish_year`, first `publisher`, the first `isbn` that `Isbn.normalize` accepts, cover from `cover_i`, `portuguese = "por" in language`; `description`/`pageCount` null.
  - `queryIsEncoded`: searching `Eça & "Cia" #1` → the recorded request path contains `q=E%C3%A7a+%26+%22Cia%22+%231` (Google) and the same `q=` for Open Library, plus the exact parameters in Global Constraints; the Google key appears as `&key=k` only when given.
  - `httpErrorThrows`: a 500 makes `search` throw.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — `@Serializable` private models with `ignoreUnknownKeys`; encode with `encodeURLParameter(spaceToPlus = true)`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Public sources: Google Books and Open Library searched by text".

### Task 4: PublicBookSearch — merged, Portuguese first, ISBN through the lookup chain

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/lookup/PublicBookSearch.kt`
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/AppContainer.kt` (expose `val publicSearch`)
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/data/lookup/PublicBookSearchTest.kt`

**Interfaces:**
- Consumes: `BookTextSource`, `PublicHit` (Task 3); `IsbnQuery` (Task 1); `LookupResult`; `catalogTitleKey`, `searchKey`.
- Produces: `class PublicSearchUnavailable(cause: Throwable) : Exception(cause)`; `class PublicBookSearch(sources: List<BookTextSource>, lookup: suspend (isbn: String) -> LookupResult)` with `suspend fun search(query: String): List<AuthorBook>`. In `AppContainer`: `val publicSearch = PublicBookSearch(listOf(GoogleBooksTextSearch(http, googleBooksApiKey), OpenLibraryTextSearch(http)), repository::lookup)`.

- [ ] **Step 1: Write the failing tests** (fake sources and lookup):
  - `mergedWithoutDuplicatesPortugueseFirst`: source A gives [X (en, isbn 1), Y (pt)], source B gives [X again (same ISBN), Z (pt, no ISBN, same title before ":" and first author as Y)] → result `[Y, X]` (Z is a duplicate of Y); order: Portuguese first, then each source's order, A before B.
  - `atMost20`.
  - `oneSourceFailingKeepsTheOther`: A throws, B gives [Z] → [Z].
  - `allSourcesFailingThrows`: both throw → `PublicSearchUnavailable`.
  - `isbnUsesTheLookupChain`: query "972-20-3650-X" → lookup called with "9789722036504", text sources not called; `Found(book)` → one `AuthorBook` with the book's title, authors, publishedYear, publisher, isbn, coverUrl, description, pageCount; `NotFound` and `NotFoundWithErrors` → empty; `Error` → `PublicSearchUnavailable`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — sources run in parallel (`coroutineScope { map { async { runCatching } } }`, rethrowing `CancellationException`); de-duplicate by ISBN, else `catalogTitleKey(title)` + `searchKey(first author)`; stable sort Portuguese first; take 20.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Public sources: merged search, Portuguese first, ISBN through the lookup chain".

### Task 5: OutsideSearchViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/library/OutsideSearchViewModel.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/library/OutsideSearchViewModelTest.kt`

**Interfaces:**
- Consumes: `matchReleases`, `ReleaseHit`, `OUTSIDE_SEARCH_LIMIT` (Task 2); `isbnQuery` (Task 1); `AllMonths`, `ReleasesUnavailable` (existing); `AuthorBook`.
- Produces:
  - `sealed interface Section<out T> { data object Idle; data object Searching; data class Results<T>(val items: List<T>); data object Empty; data object Failed }`
  - `data class OutsideSearchState(val shown: Boolean = false, val invalidIsbn: Boolean = false, val isbn: String? = null, val releases: Section<ReleaseHit> = Section.Idle, val online: Section<AuthorBook> = Section.Idle)` — `shown` is true while sections should be on screen; `isbn` is the normalized ISBN of a valid ISBN query (for "Add it yourself").
  - `enum class OutsideSection { RELEASES, ONLINE }`
  - `class OutsideSearchViewModel(releases: suspend () -> AllMonths, online: suspend (String) -> List<AuthorBook>) : ViewModel()` with `val state: StateFlow<OutsideSearchState>`, `fun onQuery(query: String, libraryHasMatch: Boolean)`, `fun retry(section: OutsideSection)`; `const val OUTSIDE_SEARCH_DELAY_MS = 800L`, `const val OUTSIDE_SEARCH_MIN = 3`.
  - Months are loaded once and reused for later queries; a failed load is retried on the next query or Retry. `online` failing with any exception → `Failed`.

- [ ] **Step 1: Write the failing tests** (`MainDispatcherTest`, `runTest`, `advanceTimeBy`):
  - `waitsForThePause`: `onQuery("cegueira", false)`; at 799 ms both sections `Idle` and `shown` false; at 800 ms both have results and `shown` true.
  - `onlyWhenTheLibraryHasNoMatch`: `onQuery("cegueira", true)` + 1 s → nothing called, `shown` false; then a library match after results hides them (`shown` false).
  - `shortQueriesWaitUnlessIsbn`: "ab" → nothing; "972-20-3650-X" → both called, `online` with "972-20-3650-X", `isbn` = "9789722036504".
  - `invalidIsbnHint`: "9789722036505" → `invalidIsbn` true and searched as text.
  - `staleResultsAreDropped` (Review Focus 1): the online fake suspends on a gate for "cegu"; `onQuery("cegueira")` follows; completing the first gate never shows its results.
  - `sameQueryDoesNotSearchAgain` (Review Focus 2): after results, `onQuery("cegueira", false)` again → no second call, state unchanged.
  - `releasesFailThenRetry` (Review Focus 4): months loader throws `ReleasesUnavailable` → `releases` `Failed`, `online` still `Results`; loader fixed, `retry(RELEASES)` → `Results`.
  - `emptyResults`: both return nothing → `Empty` and `Empty`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — one `Job` per query (cancel the previous), `delay(OUTSIDE_SEARCH_DELAY_MS)`, then the two sections in parallel children; each writes its section only if its query is still the current one.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Search: outside search — waits, cancels, one state per section".

### Task 6: The Search screen shows the sections

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/library/OutsideResults.kt` (the sections' composables)
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/library/LibraryScreen.kt` (new optional params; the empty state at "No books match")
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/MyBooksNavHost.kt` (SEARCH route wiring)
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/ScreenshotTest.kt` (new `searchEverywhereLight`), `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/library/OutsideSearchViewModelTest.kt` unchanged

**Interfaces:**
- Consumes: `OutsideSearchViewModel`, `OutsideSearchState`, `Section`, `OutsideSection` (Task 5); `ReleaseHit` (Task 2); `container.publicSearch` (Task 4); `container.releases.allMonths()`; `container.catalogBooks.put`; `Routes.releaseBook(month, id)`, `Routes.add(isbn)`.
- Produces: `LibraryScreen(..., outside: OutsideSearchViewModel? = null, onOpenRelease: (month: String, id: String) -> Unit = { _, _ -> }, onOpenCatalogBook: (AuthorBook) -> Unit = {}, onAddYourself: (isbn: String?) -> Unit = {})`. Only the SEARCH route passes `outside`.

- [ ] **Step 1: Write the failing screenshot test** `searchEverywhereLight`: the Search screen with query "cegueira", an empty library match, `releases` = `Results` with one hit ("Ensaio sobre a Cegueira", "José Saramago", month "2026-11") and `online` = `Results` with two `AuthorBook`s; waits until "Public sources" is shown; saves `search-everywhere-light.png`. Also assert with `onNodeWithText`: "New in Portugal", "Public sources", "Not here?", "Add it yourself".
- [ ] **Step 2: Run, expect FAIL** (`./gradlew :androidApp:testDebugUnitTest --tests '*ScreenshotTest.searchEverywhere*'`).
- [ ] **Step 3: Implement** — `LibraryScreen` calls `outside.onQuery(query, state.books.isNotEmpty())` in a `LaunchedEffect(query, state.books.isEmpty())`; when `outside != null` and `state.books` is empty, the empty area becomes a scrolling column: "No books match" (and "Not a valid ISBN — check the digits" when `invalidIsbn`), then, when `shown`, the "New in Portugal" and "Public sources" sections (`Searching…` / rows / empty line / error line with Retry), then "Not here?" + "Add it yourself" (`onAddYourself(state.isbn)`). Rows: cover (`ReleaseBook.listCover` / `AuthorBook.coverUrl`), title, authors, and month (New in Portugal) or year (Public sources). Wire the SEARCH route with `viewModel { OutsideSearchViewModel({ container.releases.allMonths() }, container.publicSearch::search) }`, `onOpenRelease = { m, id -> nav.navigate(Routes.releaseBook(m, id)) }`, `onOpenCatalogBook = { nav.navigate("catalog/${container.catalogBooks.put(it)}") }`, `onAddYourself = { nav.navigate(Routes.add(it)) }`.
- [ ] **Step 4: Run, expect PASS**; then the full gate and lint (Global Constraints); look at the screenshot.
- [ ] **Step 5: Commit** — "Search: New in Portugal and public sources below an empty library search".
