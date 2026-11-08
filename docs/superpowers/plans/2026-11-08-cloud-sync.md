# Cloud Library Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A signed-in user's whole library (books, quotes, challenges, ebook state, bookmarks, cover photos, account
preferences) is stored in Supabase and kept in step on every device, offline included.

**Architecture:**
- **Server:** private `library_*` tables in Supabase. Three security-definer functions merge per field by time
  (`sync_push`, `merge_library`) and serve changes after a sequence number (`sync_pull`).
- **Phone, recording:** wrappers around the Room DAOs stamp field times and queue changed rows in the same transaction.
- **Phone, sync engine:** `LibrarySync` in `shared/commonMain` pushes the queue and pulls changes. It applies them per field
  with the same rule, and is woken by Realtime pings.
- Room stays the phone's database. Signed-out use is unchanged.

**Tech Stack:**
- Kotlin Multiplatform, Room KMP (v7 → v8), Compose Multiplatform.
- supabase-kt 3.8.0: auth, postgrest, storage, plus **realtime-kt** (new).
- Postgres with pgTAP, Ktor MockEngine.

**Spec:** `docs/superpowers/specs/2026-11-08-cloud-sync-design.md`

## Global Constraints

- Supabase free plan only: no new paid services. Storage bucket `covers`, path `<owner>/<book uuid>.jpg`.
- Every server function:
  - is `security definer`;
  - starts with `require_user()`;
  - has `set search_path = public, pg_temp`;
  - has `revoke all … from public, anon; grant execute … to authenticated`.
- RLS on every `library_*` table: `select` only where `owner = auth.uid()`. There are no direct write policies; writes go
  through the functions.
- **Field-time rules:**
  - Times are epoch milliseconds.
  - A missing stored time counts as −1.
  - An incoming time is capped at server `now()` + 60 000 ms.
  - An equal time keeps the stored value.
  - Deletion is the field `deleted`, with its own time.
- Every query that feeds a screen, a backup or the community ignores rows with `deleted = 1`.
- **Signed out:** sync is off. Deletes remove rows for good, as today, and nothing is queued.
- **Only the device where a change was made publishes it** to `shared_books` or the feed. Pulled changes never call
  `ShareTracker.changed`.
- **Retry waits after a failure:** 5 s, 15 s, 60 s, then 300 s, repeating the last. Retry straight away when the
  connection returns.
- **Batches:**
  - `merge_library`: 100 books per call.
  - `sync_pull`: `max` defaults to 500 and is clamped to 1–1000.
  - `sync_push`: up to 200 changes per call.
- **Status copy:**
  - "Synced just now"
  - "Synced N minutes ago"
  - "Syncing…"
  - "Offline · N changes waiting"
  - "Couldn't sync · Retry"
  - "Sign in again to sync"
  - "Cover photos not syncing: storage full"
- **Other copy:**
  - "Combining your libraries… X of Y"
  - Sign-out dialog: "Keep a copy of your library on this device?", with buttons "Keep" (default) and "Remove"
  - "Ebook on another device"
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- The gate is in "Verification" at the end. Lint is run separately.

**Plan decisions** (refining the spec; executors treat them as given):
- **One queue entry per row.** `sync_queue` holds one entry per changed `(table, rowId)`. A push sends the row's full current
  fields and their times. Under the per-field rule this is equivalent to sending only the changed fields, and it merges
  repeated edits into one entry.
- **Covers download in the background.** Cover photos brought by a pull are downloaded in the background after that pull,
  at most 3 at once, not on first display. The result is the same with no screen hooks. If wrong, it costs downloads of
  covers for books never opened (about 100 KB each).

## Review Focus

1. **Re-adding a deleted book's ISBN.** Re-adding a book whose ISBN belongs to a soft-deleted row (synced user deletes,
   then scans the same book) must revive that row, not fail with "duplicate ISBN" or crash. Owner: Task 5,
   `reAddingDeletedIsbnRevivesTheRow`.
2. **Killed mid-sync.** If the app is killed after a local write or mid-push, nothing is lost and nothing is duplicated.
   The queue entry is written in the write's transaction, and pushing the same change twice changes nothing. Owners:
   Task 1, `push_is_idempotent`; Task 7, `crashBetweenPushAndConfirmResendsSafely`.
3. **Pull meets unsent local edits.** A pull arriving for a row with unsent local edits must not overwrite the newer local
   fields. Owner: Task 4, `remoteOlderFieldDoesNotOverwriteLocal`.
4. **Child rows before their book.** A quote, bookmark or ebook row pulled before its book exists locally is held and
   applied once the book arrives, never dropped or crashed on. Owner: Task 7, `childBeforeBookIsAppliedLater`.
5. **Interrupted first merge.** A first merge of a large library interrupted halfway resumes without duplicating books.
   Owner: Task 8, `interruptedFirstMergeResumesWithoutDuplicates`.

---

### Task 1: Server: library tables, `sync_push`, `sync_pull`, Realtime ping

**Files:**
- Create: `supabase/migrations/2026111000_library_sync.sql`
- Create: `supabase/tests/library_sync_test.sql`
- Modify: `docs/social-setup.md` (new section "3e. Library sync": apply via SQL Editor and record version `2026111000`
  `library_sync`, as section 3d does)

**Interfaces:**
- Produces, in SQL:
  - **Tables:** `library_books`, `library_quotes`, `library_challenges`, `library_ebooks`, `library_bookmarks`,
    `library_prefs`.
    - Each has `owner uuid not null references auth.users on delete cascade`, `id text not null`,
      `field_times jsonb not null default '{}'`, `deleted boolean not null default false`, and
      `seq bigint not null default nextval('library_seq')`.
    - Primary key `(owner, id)`. Index `(owner, seq)`.
  - **Columns** (snake_case of the Room fields; the codec in Task 4 maps them):
    - `library_books`: isbn, title, authors, publisher, published_year, page_count, cover_url, description, status,
      rating, notes, date_added, date_read, date_started, current_page, progress_percent, review, tags, series,
      series_number, lent_to, lent_on.
    - `library_quotes`: book_id (the book uuid), text, page, date_added, position.
    - `library_challenges`: id = year as text, goal.
    - `library_ebooks`: id = book uuid, format, file_name, size, date_added, position, last_opened.
    - `library_bookmarks`: book_id, position, label, date_added.
    - `library_prefs`: id = key, value.
    - Text length checks match `shared_books` where shared (title ≤ 300, review ≤ 2000). Notes ≤ 20 000, quote text ≤ 5 000.
  - **`sync_push(changes jsonb) returns jsonb`**
    - Each change is `{"table":"books|quotes|challenges|ebooks|bookmarks|prefs","id":…,"fields":{…},"times":{…}}`.
    - Returns `{"applied":[ids],"set_aside":[{"id":…,"reason":…}]}`.
    - A row that violates a check is set aside; the rest commit.
  - **`sync_pull(after bigint, max int default 500) returns jsonb`**
    - Returns `{"rows":[{"table","id","fields","times","deleted","seq"}],"last":seq}`, ordered by `seq`.
  - **Realtime:** each successful `sync_push` (and `merge_library`, Task 2) calls
    `realtime.send('{}'::jsonb, 'changed', 'library:' || owner, true)` (private).
  - A `realtime.messages` select policy lets a user receive only topic `'library:' || auth.uid()`.

- [ ] **Step 1: Write the pgTAP tests** in `supabase/tests/library_sync_test.sql`, in the style of
  `suggested_readers_test.sql`: users ana and bruno, `set local role authenticated` plus `request.jwt.claims`. Cases, by
  description:
  - `push_inserts_then_pull_returns_it`: ana pushes book `b1` with title "Os Maias" at t=1000. `sync_pull(0)` returns one
    row with title "Os Maias" and `times.title = 1000`.
  - `newer_field_wins_older_loses`: push title "A" at 2000, then "B" at 1500. Stored title is "A". Rating 4 at 1500 is
    then accepted, because rating had no time.
  - `equal_time_keeps_stored`: "A" at 3000, then "Z" at 3000 → "A".
  - `future_time_capped`: a time of now + 1 day is stored as ≤ now + 60 000. A later edit at now + 2 minutes then wins.
  - `delete_beats_older_edit_and_newer_edit_revives`:
    - `deleted=true` at 5000 → `deleted` is true;
    - title at 4000 → still deleted;
    - then `deleted=false` at 6000 with title "C" → deleted is false and the title is "C".
  - `push_is_idempotent`: the same change pushed twice gives one row and an unchanged `field_times`. The second push still
    gets a new `seq`, which is acceptable; assert row count = 1.
  - `bad_row_set_aside_rest_applied`: a 301-character title is set aside with a reason, and a second valid change in the
    same call is applied.
  - `pull_only_mine_after_cursor`: bruno's rows never appear for ana. `sync_pull(last)` after a pull returns nothing new.
    `max` is clamped to 1000.
  - `anon_cannot_call`: `sync_push` and `sync_pull` throw `42501`.
  - `rls_hides_other_owner`: as bruno, `select count(*) from library_books where owner = ana` = 0.

- [ ] **Step 2: Push the branch and run CI `supabase.yml`.** Expected: FAIL (the functions don't exist). There's no Docker
  locally; pgTAP runs only in CI (as for BookNerd, Task 1).

- [ ] **Step 3: Write the migration.** `sync_push` is plpgsql:
  - For each change, it whitelists the table name and builds `merged = stored row as jsonb`.
  - For every incoming field `f` with `capped(times[f]) > coalesce(stored.field_times[f], -1)`, it sets `merged[f]` and
    `field_times[f]`.
  - It upserts with `jsonb_populate_record` inside a `begin … exception when check_violation or invalid_text_representation
    or string_data_right_truncation … end` block per change, so one bad row is set aside.

- [ ] **Step 4: Run CI.** Expected: `library_sync_test` passes, and every earlier pgTAP file still passes.

- [ ] **Step 5: Commit:** `git add supabase docs/social-setup.md`, message "Server: library sync tables, sync_push, sync_pull".

### Task 2: Server: `merge_library`, the covers bucket

**Files:**
- Modify: `supabase/migrations/2026111000_library_sync.sql` (it isn't applied anywhere yet)
- Modify: `supabase/tests/library_sync_test.sql`

**Interfaces:**
- Consumes: Task 1 tables and the merge rule (reuse its per-field merge as an internal function `_merge_row(table, owner,
  id, fields, times)`).
- Produces: **`merge_library(books jsonb) returns jsonb`**
  - Input: `books` is a list of `{"id": local uuid, "fields":{…},"times":{…}}`.
  - Output: `{"ids": {local uuid: cloud uuid}}`.
  - Matching: by ISBN first, then by `lower(unaccent-free title before ':')` plus the first author before ',', both
    compared with `lower()` after `translate()` of Portuguese accents, as `searchKey`/`catalogTitleKey` do. Only rows not
    deleted are matched.
  - A match is merged with `_merge_row` under the cloud id. A book with no match is inserted with its own id.
- Also produces:
  - Storage bucket `covers` (private).
  - Policies on `storage.objects` for `bucket_id = 'covers' and (storage.foldername(name))[1] = auth.uid()::text`:
    select, insert, update and delete.

- [ ] **Step 1: Add pgTAP cases:**
  - `merge_matches_by_isbn_keeps_cloud_id`: the cloud has `c1` with ISBN 9789722036504. Merging local `l1` with the same
    ISBN and a newer rating gives the map `{l1: c1}`, the cloud row's rating updated, and one row.
  - `merge_matches_by_title_and_first_author`: the cloud has "Ensaio sobre a Cegueira" by "José Saramago". Merging
    "ensaio sobre a cegueira: romance" by "Jose Saramago, Outro" gives a match.
  - `merge_inserts_unmatched`: a new book is inserted under its own id.
  - `merge_ignores_deleted_cloud_rows`: a deleted cloud book with the same ISBN isn't matched.
  - `merge_anon_cannot_call`.
  - `covers_bucket_private`: `select public from storage.buckets where id='covers'` = false.

- [ ] **Step 2: Run CI.** Expected: the new cases FAIL.
- [ ] **Step 3: Implement it in the migration.**
- [ ] **Step 4: Run CI.** Expected: PASS.
- [ ] **Step 5: Commit:** "Server: merge_library and the covers bucket".

### Task 3: Room v8: sync columns, sync tables, migration

**Files:**
- Modify:
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/db/BookEntity.kt`, `QuoteEntity.kt`, `EbookEntity.kt`,
    `ChallengeEntity.kt`, `BookDao.kt`, `AppDatabase.kt`
  - `shared/schemas/…/8.json` (generated)
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/data/db/SyncEntities.kt`
- Test: `shared/src/androidHostTest/kotlin/com/mcosta/mybooks/data/db/MigrationTest.kt` (add a v7 → v8 case)

**Interfaces:**
- **Produces, on entities:**
  - `BookEntity`, `QuoteEntity`, `EbookEntity` and `BookmarkEntity` gain `uuid: String = newUuid()`,
    `fieldTimes: String = "{}"` and `deleted: Boolean = false`.
  - `ChallengeEntity` gains `fieldTimes` and `deleted`.
  - Unique index on `uuid` for books, quotes and bookmarks. Ebooks are keyed by `bookId` and have a `uuid` column.
- **Produces, new tables:**
  - `SyncQueueEntity(@PrimaryKey(autoGenerate) id: Long, table: String, rowId: String, createdAt: Long)`, with a unique
    index on `(table, rowId)`.
  - `SyncStateEntity(@PrimaryKey key: String, value: String)`.
- **Produces, DAOs:**
  - `SyncQueueDao`: `enqueue(table, rowId, createdAt)` (insert, ignore on conflict), `batch(limit: Int): List<SyncQueueEntity>`,
    `remove(ids: List<Long>)`, `count(): Flow<Int>`, `clear()`.
  - `SyncStateDao`: `get(key): String?`, `set(key, value)`, `clear()`.
  - `fun newUuid(): String` in `SyncEntities.kt`.
- **`BookDao` changes:**
  - Every existing query adds `deleted = 0`.
  - New `findByIsbnAny(isbn): BookEntity?` (includes deleted), `getByUuid(uuid): BookEntity?`, `getAllAny(): List<BookEntity>`.
  - Matching `getByUuid` and `getAllAny` are added to QuoteDao, EbookDao, BookmarkDao and ChallengeDao (`getByYearAny`).
- **`MIGRATION_7_8`:**
  - Adds the columns.
  - Fills `uuid` with a random v4 from `randomblob`. Books with a `share_state` row get `uuid = share_state.shareId`.
  - Creates the indexes and the two tables, exactly as Room generates them in `8.json`.
  - Database version 8.

- [ ] **Step 1: Write the failing migration test** `migrate7To8KeepsRowsAndGivesUuids` in `MigrationTest.kt`.
  - Build a v7 database with:
    - 3 books, one of them shared with `share_state.shareId = "1f0e…"` (a fixed uuid string);
    - 2 quotes, 1 ebook, 1 bookmark, 1 challenge.
  - Migrate with all migrations, then assert:
    - all rows are present with unchanged fields;
    - every uuid matches `^[0-9a-f-]{36}$` and is unique;
    - the shared book's uuid equals its `shareId`;
    - `fieldTimes = "{}"` and `deleted = 0`;
    - `sync_queue` and `sync_state` exist and are empty.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests '*MigrationTest*'`. Expected: FAIL (no
  `MIGRATION_7_8`).
- [ ] **Step 3: Implement the entities, DAOs and migration.** Register the migration in `createDatabase`. Generate
  `8.json` with `:shared:kspDebugKotlinAndroid` and copy its CREATE statements into the migration, as `MIGRATION_6_7`
  does.
- [ ] **Step 4: Add a DAO test** `deletedRowsAreHidden` in `BookDaoTest`: `observe`, `getAll`, `findByIsbn` and
  `findWithoutIsbn` skip a row with `deleted = 1`, and `findByIsbnAny` returns it. Run the migration and DAO tests.
  Expected: PASS.
- [ ] **Step 5: Run the whole gate** (database version bumps touch the screenshots and backups). Expected: green.
- [ ] **Step 6: Commit:** "Room v8: sync columns and tables".

### Task 4: Sync rows: codec and per-field merge

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/SyncModel.kt`, `SyncCodec.kt`, `FieldTimes.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/SyncCodecTest.kt`, `FieldTimesTest.kt`

**Interfaces:**
- **Produces, in `SyncModel.kt`:**
  ```kotlin
  enum class SyncTable(val wire: String) { BOOKS("books"), QUOTES("quotes"), CHALLENGES("challenges"), EBOOKS("ebooks"), BOOKMARKS("bookmarks"), PREFS("prefs") }
  data class SyncRow(val table: SyncTable, val id: String, val fields: Map<String, JsonElement>, val times: Map<String, Long>, val seq: Long = 0)
  // "deleted" is an ordinary field in fields/times (JsonPrimitive(true/false)).
  ```
- **Produces, in `FieldTimes.kt`:**
  - `fun parseTimes(json: String): Map<String, Long>` and `fun timesJson(times: Map<String, Long>): String`.
  - `fun stamp(old: Map<String, JsonElement>?, new: Map<String, JsonElement>, oldTimes: Map<String, Long>, now: Long): Map<String, Long>`:
    each field whose value changed (or every field when `old` is null) gets `now`; the rest keep their time.
  - `fun mergeFields(local: SyncRow?, remote: SyncRow): SyncRow`: per field, the remote value wins only when
    `remote.times[f] > (local.times[f] ?: -1)`. This is the same rule as the server, minus the cap.
- **Produces, in `SyncCodec.kt`:**
  - For each entity, `fun BookEntity.toRow(coverStore: CoverStore?): SyncRow` and
    `fun SyncRow.toBookEntity(base: BookEntity?, coverStore: CoverStore?): BookEntity`. The same pair exists for quotes,
    ebooks, bookmarks, challenges and prefs.
  - Field names are the server's snake_case columns.
  - Quote, ebook and bookmark rows carry `book_id` = the book's uuid, so the codec takes a `bookUuid`/`bookId` resolver:
    `suspend (Long) -> String?` and `suspend (String) -> Long?`.
  - A local cover photo (`CoverStore.isLocal`) is written as `cover_url = "photo"`. Reading `"photo"` sets
    `coverUrl = CoverStore.PREFIX + "<uuid>.jpg"`.
  - `size` and the other Long fields are JSON numbers. Tags travel as the stored "\n"-joined string.

- [ ] **Step 1: Write failing tests:**
  - `bookRoundTripsEveryField`: a `BookEntity` with every field set survives `toRow` → `toBookEntity` unchanged, id
    and uuid included.
  - `coverPhotoTravelsAsMarker`: `coverUrl = "cover:abc.jpg"` gives `cover_url = "photo"`, which reads back as
    `"cover:<uuid>.jpg"`. A web URL is unchanged.
  - `quoteCarriesBookUuid`: `book_id` = the resolved uuid, and the quote reads back with the local book id.
  - `stampMarksOnlyChangedFields`: changing rating from 3 to 4 at 9000 gives `times.rating = 9000`; the title keeps 1000.
  - `remoteNewerFieldWins`: remote title at 2000 over local at 1000 gives the remote title.
  - `remoteOlderFieldDoesNotOverwriteLocal`: local notes at 5000 (unsent) against remote notes at 4000 keep the local
    notes, while the remote's newer rating at 6000 is taken.
  - `remoteDeletedNewerHidesRow`: remote `deleted=true` at 7000 over local edits at 6000 gives `deleted` true.
  - `missingLocalTimeLosesToAnyRemoteTime`: a local `{}` against remote time 0 → remote wins.
- [ ] **Step 2: Run** `./gradlew :shared:testAndroidHostTest --tests 'com.mcosta.mybooks.sync.*'`. Expected: FAIL
  (unresolved).
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Sync rows: codec and per-field merge".

### Task 5: Recording local changes (DAO wrappers)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/SyncRecorder.kt`,
  `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/SyncingDaos.kt`
- Modify:
  - `AppContainer.kt`: the repositories get the wrapped DAOs.
  - `data/PinnedTags.kt` and `HiddenSuggestions` (record pref changes).
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/SyncingDaosTest.kt`

**Interfaces:**
- Consumes: Task 3 entities and DAOs; Task 4 `stamp`, `timesJson`, `parseTimes`, `toRow`.
- **Produces, `SyncRecorder`:**
  ```kotlin
  class SyncRecorder(
      private val queue: SyncQueueDao,
      private val clock: () -> Long,
      private val enabled: () -> Boolean,
      private val transaction: suspend (suspend () -> Unit) -> Unit,
  ) {
      val changes: SharedFlow<Unit>
      suspend fun record(table: SyncTable, rowId: String)
  }
  ```
  `changes` emits after each record and wakes the engine. `record` enqueues only when `enabled()`.
- **Produces, wrappers:** `SyncingBookDao(raw: BookDao, recorder: SyncRecorder) : BookDao`, and the same for `QuoteDao`,
  `EbookDao`, `BookmarkDao` and `ChallengeDao`.
  - **When `enabled()`:**
    - `insert` sets a new uuid and stamps every field.
    - `update` and `upsert` keep the stored `uuid`, stamp the changed fields with `stamp`, and clear `deleted`.
    - `delete` sets `deleted = 1` and stamps `deleted`. `deleteForBook` does the same for each child row.
    - Each of these runs in `recorder.transaction` together with `recorder.record`.
  - **Revive:** `insert` of a book whose ISBN matches a deleted row (`findByIsbnAny`) updates that row instead (stamping
    every field, `deleted = 0`) and returns its id.
  - **When not `enabled()`:** behaviour is exactly the raw DAO's, so deletes are hard deletes.
- **Produces, prefs:** `PinnedTags` and `HiddenSuggestions` take an optional `onChanged: suspend (key: String) -> Unit`.
  `AppContainer` passes one that stores `prefs/<key>` time in `sync_state` and records `SyncTable.PREFS`.

- [ ] **Step 1: Write failing tests** (with the existing `FakeBookDao`, plus a fake `SyncQueueDao` in commonTest):
  - `insertStampsAllFieldsAndQueues`: save a book at clock 1000. Its `fieldTimes` has `title=1000` and the others, and
    the queue has `(books, uuid)`.
  - `updateStampsOnlyChangedFieldsKeepsUuid`: after `setRating`, only `rating` gets the new time and the uuid is
    unchanged.
  - `repeatedEditsQueueOnce`: three edits leave one queue entry.
  - `deleteIsSoftWhileSyncing`: `repo.delete(id)` hides the book from `observeBooks`, the row has `deleted = 1`, and its
    quotes and bookmarks are soft-deleted and queued.
  - `reAddingDeletedIsbnRevivesTheRow`: delete a book with ISBN 9789722036504, then `repo.save(Book(isbn=…))` returns
    `SaveResult.Saved` with the same id, `deleted = 0`, and the new title.
  - `signedOutBehavesAsBefore`: with `enabled = false`, delete removes the row and the queue stays empty.
  - `pinnedTagsChangeQueuesPref`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.** Wire it in `AppContainer`. The repositories receive `SyncingXDao(db.xDao(), recorder)`,
  and `transaction` is the existing Room write-transaction runner.
- [ ] **Step 4: Run** the sync tests, then the whole gate. Expected: PASS. Existing repository tests are unchanged, because
  their recorder has `enabled = false` or they use the raw fakes.
- [ ] **Step 5: Commit:** "Sync: record local changes".

### Task 6: `SyncApi`: Supabase client and a fake server

**Files:**
- Modify: `gradle/libs.versions.toml` (`supabase-realtime = { module = "io.github.jan-tennert.supabase:realtime-kt" }`),
  `shared/build.gradle.kts`
- Create:
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/SyncApi.kt`
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/social/supabase/SupabaseSync.kt`
- Modify:
  - `social/supabase/SupabaseSocial.kt`: `install(Realtime)`; `SocialServices` gains `val sync: SyncApi?`.
  - `social/Services.kt`
- Create (test): `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/FakeSyncServer.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/social/SupabaseSyncTest.kt` (MockEngine, like
  `SupabaseSocialTest`)

**Interfaces:**
- Consumes: Task 4 `SyncRow` and `SyncTable`.
- **Produces:**
  ```kotlin
  data class PushResult(val applied: List<String>, val setAside: List<Pair<String, String>>)
  data class PullPage(val rows: List<SyncRow>, val last: Long)
  class SyncException(val kind: Kind, message: String) : Exception(message) { enum class Kind { OFFLINE, AUTH, STORAGE_FULL, OTHER } }
  interface SyncApi {
      suspend fun push(rows: List<SyncRow>): PushResult
      suspend fun pull(after: Long, max: Int = 500): PullPage
      suspend fun mergeLibrary(books: List<SyncRow>): Map<String, String>
      fun pings(owner: String): Flow<Unit>
      suspend fun uploadCover(owner: String, bookUuid: String, jpeg: ByteArray)
      suspend fun downloadCover(owner: String, bookUuid: String): ByteArray?
  }
  ```
- `FakeSyncServer : SyncApi` (commonTest):
  - One in-memory store per owner, using the server's rule (with the cap) and a `seq`.
  - `failNext: SyncException?`.
  - `pingsFlow` emits on every push.
  - It can be shared by two engines, to simulate two devices.
- **Error mapping:** IO/timeouts → OFFLINE; 401/JWT expired → AUTH; storage 413 or quota → STORAGE_FULL; otherwise OTHER.
  Reuse the existing `SocialErrorMapping` approach.

- [ ] **Step 1: Write failing tests:**
  - `pushSendsRowsAsSyncPushJson`: the request is a POST to `/rest/v1/rpc/sync_push` with the body
    `{"changes":[{"table":"books","id":"u1","fields":{…},"times":{…}}]}`. `{"applied":["u1"],"set_aside":[]}` parses.
  - `pullParsesRowsAndLast`.
  - `mergeLibraryReturnsIdMap`.
  - `expiredSessionIsAuth`: a 401 gives `SyncException(AUTH)`.
  - `offlineIsOffline`.
  - `storageFullIsStorageFull`: a 413 on upload.
  - `fakeServerAppliesNewestPerField`: guards the fake against the Task 1 rule.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
  - Realtime: `client.channel("library:$owner") { isPrivate = true }`, `broadcastFlow<JsonObject>("changed")` mapped to
    `Unit`, subscribed on collection and unsubscribed on cancel.
  - Storage: `client.storage.from("covers").upload("$owner/$uuid.jpg", jpeg) { upsert = true }`. `downloadAuthenticated`
    returns null on 404.
- [ ] **Step 4: Run.** Expected: PASS. Also build `:shared:compileKotlinIosSimulatorArm64` to confirm realtime-kt
  resolves for iOS.
- [ ] **Step 5: Commit:** "Sync: Supabase client and fake server".

### Task 7: `LibrarySync` engine: push, pull, retry, status

**Files:**
- Create:
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/LibrarySync.kt`
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/RemoteApplier.kt`
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/SyncStatus.kt`
- Modify: `share/ShareTracker.kt` (add `suspend fun syncedFromElsewhere(book: Book)`)
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/LibrarySyncTest.kt`

**Interfaces:**
- Consumes:
  - Task 3 raw DAOs, `SyncQueueDao` and `SyncStateDao`.
  - Task 4 codec and `mergeFields`.
  - Task 5 `SyncRecorder.changes`.
  - Task 6 `SyncApi` and `FakeSyncServer`.
- **Produces, `SyncStatus`:**
  ```kotlin
  sealed interface SyncStatus {
      data object Off : SyncStatus
      data object Syncing : SyncStatus
      data class Synced(val at: Long) : SyncStatus
      data class Offline(val waiting: Int) : SyncStatus
      data class Failed(val waiting: Int) : SyncStatus
      data object SignInAgain : SyncStatus
  }
  ```
  - `val setAside: StateFlow<Int>`, alongside `status`. The "storage full" flag is Task 9's `CoverSync.full`.
  - `fun SyncStatus.label(now: Long): String` returns the copy in Global Constraints. The minutes are rounded down, and
    under 1 minute it says "just now".
- **Produces, `LibrarySync`:**
  ```kotlin
  class LibrarySync(api: SyncApi, db: SyncDatabase /* raw DAOs + queue + state */, recorder: SyncRecorder, applier: RemoteApplier,
                    clock: () -> Long, scope: CoroutineScope, retryDelays: List<Long> = listOf(5_000, 15_000, 60_000, 300_000)) {
      val status: StateFlow<SyncStatus>
      fun start(owner: String)          // runs Task 8's first merge if needed, then push → pull; collects recorder.changes and api.pings(owner)
      fun stop()
      suspend fun refresh()              // pull now (foreground, pull-to-refresh)
      fun connectivityRestored()         // retry now
  }
  ```
- **Produces, `RemoteApplier`:**
  - `suspend fun apply(rows: List<SyncRow>)`.
  - Books are applied first. Then child rows; those whose `book_id` uuid isn't local are kept as JSON in `sync_state`
    under key `orphans` and retried on every apply.
  - Writes go through the raw DAOs, so they are never queued and never published. Books then call
    `ShareTracker.syncedFromElsewhere(book)`, which records the pulled values as the share state's "before" values
    without publishing.
  - A row whose merged `deleted` is true is removed for good locally (not soft-deleted), along with its cover photo.
- **Push and pull:**
  - Push takes queue batches of 200. It removes the applied and set-aside entries; set-aside ones are added to `setAside`.
  - Pull loops until fewer than `max` rows come back. It stores `sync_state["seq"]` after each page is applied.

- [ ] **Step 1: Write failing tests** (with `runTest` and virtual time):
  - `localEditIsPushedAndQueueEmptied`.
  - `offlineKeepsQueueAndRetriesWithBackoff`: the fake fails with OFFLINE three times. The status is `Offline(1)`, and
    retries happen at 5 s, 15 s and 60 s of virtual time; then it succeeds and becomes `Synced`.
  - `connectivityRestoredRetriesImmediately`.
  - `authErrorShowsSignInAgainKeepsQueue`.
  - `setAsideDoesNotBlockQueue`: one row is refused and two pass. The queue is empty and `setAside` = 1.
  - `twoDevicesConverge`:
    - Devices A and B share one `FakeSyncServer`. Both start offline.
    - A changes book X's rating to 5 at t=1000; B changes X's notes to "nota" at t=1100 and Y's title at t=1200.
    - Once both are online and have pushed and pulled, X on both devices has rating 5 and notes "nota", and Y has B's
      title.
  - `pingTriggersPull`: B pushes, and A's library changes without `refresh()`.
  - `pulledChangeIsNotPublished`: the fake `ShareTracker` records no `changed` calls for pulled rows, and
    `syncedFromElsewhere` is called.
  - `childBeforeBookIsAppliedLater`: a pull page with a quote whose book arrives in the next page. After both pages the
    quote exists, attached to the book.
  - `crashBetweenPushAndConfirmResendsSafely`: push succeeds on the server but the engine is stopped before removing the
    queue entry. After a restart the same row is pushed again, and the server state and the local row are unchanged
    (idempotent).
  - `remoteDeleteRemovesLocalRow`.
  - `statusLabels`: `Synced(now - 30_000)` gives "Synced just now", `Synced(now - 300_000)` gives "Synced 5 minutes ago",
    and `Offline(3)` gives "Offline · 3 changes waiting".
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Sync: the engine".

### Task 8: First sign-in merge, sign out, share ids

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/FirstMerge.kt`
- Modify:
  - `LibrarySync.kt`: `start` runs `FirstMerge` when `sync_state["merged:<owner>"]` is absent.
  - `share/ShareTracker.kt`: new share rows use the book's uuid as `shareId` via a `uuidOf: suspend (Long) -> String?`
    constructor argument. Add `suspend fun moveShare(fromBookId: Long, toBookId: Long)`.
  - `ui/social/AccountViewModel.kt`.
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/FirstMergeTest.kt`, `AccountViewModelTest` (sign-out cases)

**Interfaces:**
- **Produces, `FirstMerge`:**
  ```kotlin
  class FirstMerge(api: SyncApi, db: SyncDatabase, applier: RemoteApplier, shares: ShareTracker?) {
      val progress: StateFlow<Pair<Int, Int>?>   // (done, total) while combining; null otherwise
      suspend fun run(owner: String)
  }
  ```
  - With no local books, it sets the flag and lets the normal pull run.
  - Otherwise it sends books not yet merged in batches of 100 to `mergeLibrary`.
    - For each pair where the cloud id differs from the local uuid, the local row takes the cloud uuid. If another local
      row already has the cloud uuid, the two are merged locally with `mergeFields`, children and the share are moved via
      `moveShare`, and the extra row is removed for good.
    - After each batch it stores `sync_state["merge-done:<owner>"]` (the local uuids done), which is what makes it
      resumable.
  - Then it queues every quote, ebook, bookmark, challenge and pref, pushes, pulls everything from seq 0, and sets
    `merged:<owner>`.
- **Produces, account sign-out:**
  - `AccountViewModel.signOut(keepLibrary: Boolean)` replaces `signOut()`. The state has `askKeepLibrary: Boolean`, shown
    when the user taps "Sign out" while sync is on.
  - Keep: clear the queue and `sync_state`, stop the engine, then sign out.
  - Remove: also delete every row of books, quotes, ebooks, bookmarks, challenges and share_state, and the local cover
    photos, through the raw DAOs. Ebook files are deleted too.

- [ ] **Step 1: Write failing tests:**
  - `emptyDeviceJustPulls`.
  - `mergeMatchesAndAdoptsCloudIds`: the cloud (fake) has "Os Maias" ISBN X with rating 3 at t=500. Local has the same
    ISBN with notes at t=900. After the merge there is one local book with the cloud uuid, rating 3 and the notes, and the
    cloud has the notes.
  - `interruptedFirstMergeResumesWithoutDuplicates`: 250 local books; the fake fails on the 2nd batch. After restarting,
    250 books are local and in the cloud, with none duplicated, and `progress` reaches (250, 250).
  - `sharedBookKeepsItsPublicIdThroughMerge`: a book shared with shareId S whose cloud twin has uuid C ends with uuid C,
    and its share moved. The old public copy is deleted through the existing unshare path, as the spec says.
  - `newShareUsesBookUuid`.
  - `signOutKeepLeavesLibraryClearsQueue`.
  - `signOutRemoveErasesLibrary`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Sync: first merge and sign-out".

### Task 9: Cover photos

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/sync/CoverSync.kt`
- Modify: `LibrarySync.kt` (call it after push and pull)
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/sync/CoverSyncTest.kt`

**Interfaces:**
- Consumes: Task 6 `uploadCover`, `downloadCover` and `STORAGE_FULL`; `CoverStore.put(name, jpeg)`, `read(ref)`.
- **Produces:**
  ```kotlin
  class CoverSync(api: SyncApi, covers: CoverStore, state: SyncStateDao) {
      val full: StateFlow<Boolean>
      suspend fun uploadPending(owner: String)
      suspend fun downloadMissing(owner: String, books: List<BookEntity>)
  }
  ```
- **Upload:** after a push, any book whose local `coverUrl` is a photo and whose `sync_state["cover:<uuid>"]` doesn't
  equal the photo's file name is uploaded, then marked.
- **Download:** after a pull, books with `"cover:<uuid>.jpg"` and no file are downloaded, at most 3 at once, through
  `CoverStore.put("<uuid>.jpg", …)`.
- **Storage full:** on STORAGE_FULL, `full` becomes true and uploads stop until the next successful upload attempt (tried
  again on the next start). Data sync is unaffected.

- [ ] **Step 1: Write failing tests:**
  - `takenPhotoIsUploadedOnce`.
  - `replacedPhotoIsUploadedAgain`.
  - `pulledPhotoIsDownloadedOnce`.
  - `downloadFailureIsRetriedNextPull`.
  - `storageFullStopsUploadsNotData`: STORAGE_FULL on upload sets `full` true, and a book edit still pushes.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Sync: cover photos".

### Task 10: Wiring and screens

**Files:**
- Modify:
  - `AppContainer.kt`: build `LibrarySync` when `social != null`. Start it on `SessionState.SignedIn(user)` and stop it
    otherwise. The recorder's `enabled = { sync running }`.
  - `ui/App.kt`: `LifecycleEventEffect(ON_RESUME) { container.sync?.refresh() }`.
  - Android `MainActivity` (or `androidAppContainer`): a `ConnectivityManager` callback calls `connectivityRestored()`.
  - `ui/social/AccountScreen.kt`: the status line plus Retry, "Combining your libraries… X of Y", the sign-out dialog.
  - `ui/library/LibraryScreen.kt` and `ui/home/HomeScreen.kt`: `PullToRefreshBox` calling `refresh()`, only when sync is
    on.
  - `ui/detail/BookDetailScreen.kt` Ebook card: "Ebook on another device" when the ebook record exists but its file
    doesn't. Adding the file links it to the same record (EbookRepository upsert keeps the record's uuid via Task 5).
- Test:
  - `androidApp/src/test/kotlin/com/mcosta/mybooks/ScreenshotTest.kt`: `accountSyncLight` (Synced and Offline · 3),
    `accountCombiningDark`, `signOutDialogLight`, `ebookElsewhereLight`.
  - `AccountViewModelTest`: the status is exposed.

**Interfaces:**
- Consumes: Task 7 `status`, `refresh` and `connectivityRestored`; Task 8 `progress` and `signOut(keepLibrary)`; Task 9
  `full`.
- Produces: `AppContainer.sync: LibrarySync?` (null without the community).

- [ ] **Step 1: Write failing tests:** screenshot cases asserting the copy exists:
  - "Synced just now"
  - "Offline · 3 changes waiting"
  - "Combining your libraries… 120 of 340"
  - "Keep a copy of your library on this device?", "Keep", "Remove"
  - "Ebook on another device"
- [ ] **Step 2: Run** `./gradlew :androidApp:testDebugUnitTest --tests '*ScreenshotTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run** the full gate and lint, and look at the new screenshots. Expected: green, and each screen reads
  well in light and dark.
- [ ] **Step 5: Commit:** "Sync: wiring and screens".

## Verification

- **Gate:** `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`
- **Lint:** `./gradlew :androidReader:lintDebug :androidApp:lintDebug -q`
- **iOS:** `./gradlew :shared:compileKotlinIosSimulatorArm64 -q`
- **Server:** the CI `supabase.yml` passes.
- **Rollout** (with the user):
  1. Apply `2026111000_library_sync.sql` via the SQL Editor and record it.
  2. Verify via REST that `sync_pull` exists (401/42501).
  3. Back up the phone's database.
  4. Install, sign in, and check that the status reaches "Synced just now".
