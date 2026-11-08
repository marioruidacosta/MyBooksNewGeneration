# Community Feed, Evolved — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Community cards show the update and then the book in its own panel (blurb, pages, one-tap Want to read), several added books group into one card, the Friends tab opens with a row of what friends are reading, and a book's page lists friends who have it.

**Architecture:** The server stores three more fields per shared book and returns them with feed entries; two new security-definer functions list friends' current books and friends with an ISBN. On the phone, a pure `groupFeed` turns entries into feed items, `ActivityViewModel` adds the friends row and each book's shelf in the user's library, and the screens are rebuilt from the chosen mock-ups.

**Tech Stack:** Supabase Postgres (plpgsql, pgTAP via `supabase test db` in CI), Kotlin Multiplatform, Compose Multiplatform, supabase-kt with Ktor MockEngine in tests.

**Spec:** `docs/superpowers/specs/2026-11-08-community-feed-design.md`

## Global Constraints

- Nothing new is visible beyond what's shared: friends only (`are_friends`), owners with `share_activity`, not blocked either way (`is_blocked_with`), books not unshared (they aren't in `shared_books`).
- `blurb` ≤ 600 characters (description, whitespace collapsed); `pages` 1..20000; `current_page` ≥ 0; all nullable. Blurb/pages/current page alone never create activity.
- Friends row: Friends tab only; status reading; newest `updated_at` first; at most 20; hidden when empty, signed out or failing.
- Grouping: same person, same kind, kind ∈ {WANT_TO_READ, WANT_TO_BUY}, each within 24 h of the previous; up to 4 covers then "+N"; no heart on groups; a group of one is a single card.
- Copy, exactly: "is on page {p} of {n}"; "is {x}% through" (existing); "added {n} books to To read" / "added {n} books to To buy"; "Want to read"; "✓ In your library · {shelf label}" (shelf labels: "To read", "Reading", "Read", "To buy"); "Friends are reading"; "See all"; "{name} · {x}%"; "Friends who have this book"; "read it", "is reading it · {x}%", "wants to read it", "wants to buy it".
- Server functions: `revoke all … from public, anon; grant execute … to authenticated`, like `book_reviews`.
- Common code: no `RegexOption.DOT_MATCHES_ALL`, no `FileSystem.SYSTEM`.
- Gate: `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`, then `./gradlew :androidReader:lintDebug :androidApp:lintDebug -q` separately.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. An older app version (or the old MyBooks app) calling `feed` after the migration: it must keep working; the new columns are extra and ignored (Task 1 test `old feed columns unchanged`).
2. A friend who turned sharing off, or who blocked me, must vanish from the row and the book section at once (Task 1 tests `friends_reading hides non-sharing and blocked`).
3. A long description with newlines and tabs: the blurb is collapsed to single spaces and cut at 600 characters, never rejected by the server's check (Task 2 test `blurbIsCollapsedAndCut`).
4. Tapping "Want to read" twice quickly, or on a book already added through another card: one book, no duplicate, the button shows the shelf (Task 5 test `wantToReadTwiceAddsOnce`).
5. A page boundary inside a run of "wants to read": after "load more", one group, not two (Task 3 test `pageBoundaryRejoins`).

---

### Task 1: Server — blurb, pages and current page; friends reading; friends with a book

**Files:**
- Create: `supabase/migrations/2026110800_community_feed.sql`
- Create: `supabase/tests/community_feed_test.sql`
- Modify: `docs/social-setup.md` (one "Community feed" paragraph: run `npx supabase db push --remote` again; lists `2026110800_community_feed.sql`)

**Interfaces:**
- Produces: `shared_books.blurb text`, `.pages int`, `.current_page int`; `publish_book(book jsonb, before jsonb)` reads `book->>'blurb'`, `'pages'`, `'current_page'`; `activity_entries` gains `blurb`, `pages`, `current_page` (appended); `feed(...)` and `person_activity(...)` return the old columns plus `book_blurb text, book_pages int, book_current_page int` (appended last);
  `friends_reading() returns table (actor_id uuid, username text, display_name text, avatar_path text, book_id uuid, isbn text, title text, authors text, cover_url text, pages int, current_page int, progress int, updated_at timestamptz)`;
  `friends_with_book(p_isbn text) returns table (actor_id uuid, username text, display_name text, avatar_path text, status book_status, rating int, progress int, review text)` (review = first 200 characters, '' when none).

- [ ] **Step 1: Write the pgTAP tests** in `community_feed_test.sql`, following `book_reviews_test.sql` (users inserted into `auth.users`, profiles, friendships, `set local role authenticated` + `request.jwt.claims`). Cases, each a named `ok`/`is`/`results_eq`:
  - `blurb, pages and current page stored and returned by feed`;
  - `blurb or pages alone create no activity` (publish the same book again with only a new blurb: activity count unchanged);
  - `old feed columns unchanged` (the first 17 columns of `feed` keep their names and order);
  - `friends_reading lists friends' reading books, newest first, at most 20`;
  - `friends_reading hides non-sharing and blocked` (a friend with `share_activity` false, a friend who blocked the caller, a blocked friend, a stranger: none listed);
  - `friends_with_book lists friends with that ISBN, read first` (read, reading, to read, to buy order; strangers and non-sharing excluded);
  - `anon cannot call them`.
- [ ] **Step 2: Write the migration** — `alter table shared_books add column …` with the checks from Global Constraints; `create or replace view activity_entries` with the three columns appended; `drop function feed(text, timestamptz, bigint, int); drop function person_activity(uuid, timestamptz, bigint, int);` and re-create them with the appended return columns (bodies as in `2026110200_social_part2.sql`, selecting `e.blurb, e.pages, e.current_page`); `create or replace function publish_book` (body copied from part 2, insert/update also setting the three columns; they don't take part in deciding activity); the two new functions (`language sql stable security definer set search_path = public`, `require_user()`); revokes and grants for all four re-created/new functions.
- [ ] **Step 3: Run the tests.** Locally only if `supabase start` works (Docker); otherwise push the branch and read the `supabase` workflow run. Expected: all pass. (A failing first run is the RED; fix and push again.)
- [ ] **Step 4: Commit** — "Server: blurb, pages and current page; friends reading; friends with a book".

### Task 2: Phone models, sharing and the Supabase calls

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/social/model/Activity.kt`, `social/Services.kt` (ActivityService), `social/supabase/Rows.kt`, `social/supabase/SupabaseSocial.kt`, `share/PublicState.kt`
- Modify: `shared/src/commonTest/kotlin/com/mcosta/mybooks/social/FakeSocial.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/social/supabase/SupabaseSocialTest.kt`, `shared/src/commonTest/kotlin/com/mcosta/mybooks/share/PublicStateTest.kt` (create if absent)

**Interfaces:**
- Produces:
  - `PublicBookState(..., blurb: String? = null, pages: Int? = null, currentPage: Int? = null)`; `FeedBook(..., blurb: String? = null, pages: Int? = null, currentPage: Int? = null)`;
  - `data class FriendReading(val person: Person, val book: FeedBook, val progress: Int?, val updatedAt: Instant)`;
  - `data class FriendWithBook(val person: Person, val status: ReadingStatus, val rating: Int?, val progress: Int?, val review: String)`;
  - `ActivityService.friendsReading(): List<FriendReading>` and `friendsWithBook(isbn: String): List<FriendWithBook>` (default implementations returning `emptyList()` so other fakes compile);
  - `const val BLURB_MAX = 600`; `fun blurbOf(description: String?): String?` in `PublicState.kt`;
  - `publicFieldsDiffer` also compares `description`, `pageCount`, `currentPage`.
- Consumes: Task 1's column and function names.

- [ ] **Step 1: Write the failing tests**
  - `blurbIsCollapsedAndCut`: `blurbOf("Uma\n\tsátira  deliciosa")` == "Uma sátira deliciosa"; a 700-character description → 600 characters; blank or null → null.
  - `publicStateCarriesPagesAndBlurb`: a `Book(pageCount = 344, currentPage = 182, description = "…")` → `publicState("s").pages == 344`, `.currentPage == 182`, `.blurb` set; `publicFieldsDiffer` true when only `currentPage` changes.
  - In `SupabaseSocialTest`: `publishSendsBlurbAndPages` (the `publish_book` body has `blurb`, `pages`, `current_page`); `feedReadsBookExtras` (a feed row with `book_blurb`, `book_pages`, `book_current_page` → `FeedBook` fields); `feedWithoutExtrasStillReads` (an old-shaped row → nulls); `friendsReadingParsed` and `friendsWithBookParsed` (RPC names `friends_reading`, `friends_with_book` with `{"p_isbn": …}`; rows → models, `status` "to_buy" → `WISHLIST`).
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*SupabaseSocialTest*' --tests '*PublicStateTest*'` — expect FAIL (unresolved names).
- [ ] **Step 3: Implement** the Interfaces above; `FakeActivity` gains `var reading = listOf<FriendReading>()`, `var withBook = mapOf<String, List<FriendWithBook>>()`, `var failReading = false` and implements both calls.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Community: share blurb and pages; friends reading and friends with a book calls".

### Task 3: Grouping the feed

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/FeedGrouping.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/social/FeedGroupingTest.kt`

**Interfaces:**
- Produces: `sealed interface FeedItem { val key: String; data class Single(val entry: FeedEntry) : FeedItem; data class Group(val actor: Person, val kind: ActivityKind, val entries: List<FeedEntry>) : FeedItem }` (keys `"e:{id}"` and `"g:{first entry id}"`); `fun groupFeed(entries: List<FeedEntry>): List<FeedItem>`; `const val GROUP_WINDOW_HOURS = 24`.

- [ ] **Step 1: Write the failing tests** — `fiveWantsToReadBecomeOneGroup` (newest first, 1 h apart → one `Group` of 5); `otherKindsNeverGroup` (three STARTED → three `Single`); `anotherPersonBreaksTheRun`; `differentKindsBreakTheRun` (WANT_TO_READ then WANT_TO_BUY → two items); `moreThan24HoursApartBreaks` (gap 25 h between neighbours); `aGroupOfOneIsSingle`; `pageBoundaryRejoins` (Review Focus 5: `groupFeed(page1 + page2)` where the run spans both → one group).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — one pass over entries in order; extend the current run when actor id, kind and the 24 h gap to the previous entry match and kind is groupable.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Community: group books added at once".

### Task 4: Card text

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/FeedText.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/social/FeedTextTest.kt`

**Interfaces:**
- Produces: `actionText(entry)` — PROGRESS: "is on page {currentPage} of {pages}" when both known (from `entry.book`), else today's "is {x}% through"; RATED and REVIEWED: "rated it" / "reviewed" (stars move to their own line); `fun starsText(rating: Int?): String?` ("★★★★☆", null for null); `fun groupText(group: FeedItem.Group): String`; `fun shelfLabel(status: ReadingStatus): String` (if one exists in `ui/home`, reuse it instead and note the ruling); `fun friendWithBookText(f: FriendWithBook): String`.

- [ ] **Step 1: Write the failing tests** with the exact copy from Global Constraints (`progressShowsPagesWhenKnown`, `progressFallsBackToPercent`, `ratedLineAndStars`, `groupTextToReadAndToBuy`, `friendWithBookTexts` for each status). Update the existing RATED expectation to "rated it".
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Community: card text for pages, stars and groups".

### Task 5: ActivityViewModel — items, the friends row, each book's shelf, Want to read

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/ActivityViewModel.kt`, `ui/social/FeedActions.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/social/ActivityViewModelTest.kt`

**Interfaces:**
- Consumes: `groupFeed`, `FeedItem` (Task 3); `friendsReading()`, `FriendReading` (Task 2).
- Produces: `ActivityState` gains `items: List<FeedItem>` (always `groupFeed(entries)`), `reading: List<FriendReading>` (Friends tab; empty otherwise or on failure), `shelves: Map<String, ReadingStatus>` (FeedBook.shareId → the user's shelf for books in the library, for every loaded entry's and reading row's book, kept current as the library changes); `fun wantToRead(book: FeedBook)` and `fun wantToBuy(book: FeedBook)` (add through `LibraryShelf.add`; message as today); `LibraryShelf.statusOf(book: FeedBook): ReadingStatus?`.

- [ ] **Step 1: Write the failing tests** — `friendsTabLoadsTheReadingRow`; `everyoneTabHasNoReadingRow`; `readingRowFailingStaysHidden` (`failReading` → `reading` empty, feed still shown, no message); `itemsAreGrouped`; `shelvesShowBooksInTheLibrary` (a library book with the entry's ISBN → `shelves[shareId] == READ`; by title and first author when no ISBN); `wantToReadAddsAndUpdatesTheShelf` (→ `TO_READ`, message "Added to To read"); `wantToReadTwiceAddsOnce` (Review Focus 4: two quick calls → one library book).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — observe `repository.observeBooks(null, "")` and recompute `shelves` with a `LibraryIndex`-style lookup returning status; serialize adds with a `Mutex` (or a set of share ids in flight).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — "Community: grouped items, friends reading row, Want to read from the card".

### Task 6: The Community screen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/FeedEntryCard.kt` (rebuilt as card B), `ui/social/ActivityScreen.kt`, `ui/MyBooksNavHost.kt` (route `Routes.FRIENDS_READING = "friends-reading"`)
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/FeedGroupCard.kt`, `ui/social/FriendsReading.kt` (`FriendsReadingRow`, `FriendsReadingScreen`)
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/ScreenshotTest.kt` (`communityLight`, `communityDark`)

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces: `FeedEntryCard(entry, shelf: ReadingStatus?, mine: Boolean, onPerson, onBook, onWantToRead, onWantToBuy, onOpenMine, onLike, onLikers, onReport)`; `FeedGroupCard(group, onPerson, onBook: (FeedBook) -> Unit)`; `FriendsReadingRow(reading, onBook, onSeeAll)`; `FriendsReadingScreen(viewModel, onBack, onBook)`.

- [ ] **Step 1: Write the failing screenshot test** `community` — a `FakeSocial` signed in with: a reading row of 3 friends; a progress entry with pages; a reviewed entry with stars and review; a book already in the library (button "✓ In your library · Read"); a group of 5 "wants to read". Waits until "Friends are reading" is shown; asserts the texts "Friends are reading", "Want to read", "✓ In your library · Read", "added 5 books to To read", "is on page 182 of 344" exist.
- [ ] **Step 2: Run, expect FAIL** (missing composables/params).
- [ ] **Step 3: Implement** the mock-up B card (update line; stars line; review up to 4 lines in quotes, tap expands; panel with 64×96 cover, title, "author · N pages", progress bar on progress entries, blurb 3 lines, full-width button, ⋮ menu with "To buy"); the group card (up to 4 covers 56×84 + "+N" tile that expands); the row (72×108 covers, avatar on the corner, thin bar, "{name} · {x}%", "See all"); `ActivityScreen` lists `state.items` with `key = item.key`, the row as the first item on the Friends tab when non-empty.
- [ ] **Step 4: Run, expect PASS**; look at both screenshots.
- [ ] **Step 5: Commit** — "Community: richer cards, grouped card and friends reading row".

### Task 7: A book's page — friends who have this book

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/detail/BookDetailViewModel.kt`, `ui/detail/BookDetailScreen.kt` (or `BookExtras.kt`, where the community reviews section lives), `ui/MyBooksNavHost.kt`
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/detail/FriendsWithBook.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/detail/BookDetailViewModelTest.kt`, `ScreenshotTest.kt` (`bookPage*` shows the section)

**Interfaces:**
- Consumes: `friendsWithBook(isbn)`, `FriendWithBook` (Task 2); `friendWithBookText` (Task 4).
- Produces: `BookDetailViewModel(..., friendsWithBook: (suspend (isbn: String) -> List<FriendWithBook>)? = null)` with `val friends: StateFlow<List<FriendWithBook>>` (empty when null, no ISBN, signed out, or on failure); `FriendsWithBookSection(friends, onPerson: (Person) -> Unit)`.

- [ ] **Step 1: Write the failing tests** — `friendsWhoHaveTheBook` (ISBN → the list); `noIsbnNoFriendsCall`; `friendsFailingIsEmpty`; screenshot `bookPage` includes "Friends who have this book" and "read it".
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**; NavHost passes `container.social?.let { s -> { isbn -> if (signed in) s.activity.friendsWithBook(isbn) else emptyList() } }` and opens `Routes.profile(person.id)` on tap.
- [ ] **Step 4: Run, expect PASS**; then the full gate and lint (Global Constraints).
- [ ] **Step 5: Commit** — "Book page: friends who have this book".
