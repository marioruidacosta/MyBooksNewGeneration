# Separate Libraries per Account Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone keeps a separate library for each account that signs in on it, plus its own no-account library, so
nothing is ever merged or erased across accounts.

**Architecture:**
- Each library is its own Room database plus cover and ebook folders: a `LibrarySpace`.
- A `Libraries` holder keeps the active space and switches it on sign-in and requested sign-out.
- `AppContainer` keeps its device-wide services, and its library members (`repository`, `quotes`, `ebooks`, `sync`…)
  delegate to the active space. Existing callers (screens, reader, widget, alerts worker) don't change.
- The UI is keyed on the active library, with a per-library `ViewModelStore`, so a switch rebuilds every screen.

**Tech Stack:** Kotlin Multiplatform, Room KMP v8, Compose Multiplatform, Robolectric (androidHostTest, androidApp tests).

**Spec:** `docs/superpowers/specs/2026-11-09-separate-libraries-design.md` (builds on `2026-11-08-cloud-sync-design.md`).

## Global Constraints

- **Paths:**
  - no-account library: `databases/mybooks.db`, `files/covers/`, `files/ebooks/` (unchanged);
  - account `<id>`: `databases/libraries/<id>.db`, `files/libraries/<id>/covers/`, `files/libraries/<id>/ebooks/`.
- **Per library:** books, quotes, goals, ebook records and bookmarks, sync queue and state, share records, and the
  synced preferences `home.pinnedTags` and `suggestions.hidden`.
- **Device-wide:** appearance, notification settings, New in Portugal lists, the sign-in session, library sort order
  and grid/list.
- **Isolation:** no code path reads two libraries at once, except `LibraryTransfer`, which moves the no-account books
  into an account on request. An account library is never merged with another account's.
- **The no-account library never syncs and never publishes.**
- **Copy:**
  - "Add this phone's N books to your account?" (singular: "Add this phone's book to your account?")
  - "They'll move into your account's library and sync to your other devices."
  - buttons "Add them" and "Not now"
  - Settings → Account: "Add this phone's books"
  - error: "Couldn't open your library. Try signing in again."
- **Sign-out and expiry:**
  - Only a sign-out the user asked for (Keep or Remove) switches to the no-account library.
  - An expired session keeps the account library active in `SignInAgain`, per the cloud-sync rulings.
  - A different account signing in switches to that account's library.
- **Gate:**
  - `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && timeout 1800 ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`
  - lint: `timeout 900 ./gradlew :androidReader:lintDebug :androidApp:lintDebug -q`
  - iOS: `timeout 1200 ./gradlew :shared:compileKotlinIosSimulatorArm64 -q`
  - Any concurrency test gets a timeout.
- **Commit trailer:** `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Plan decisions** (refining the spec; executors treat them as given):
- **`AppContainer`'s library members become getters of the active space**, not fields. The reader, widget, alerts
  worker and screens keep calling `container.repository` etc.
- **Per-library preferences live in the library's own database**, in `sync_state` under keys `pref:<key>`, through a
  `DbPreferences` with an in-memory cache loaded when the space opens. They are backed up with the database.
  Device-wide preferences stay in the device settings.
- **Choosing the owner at upgrade:**
  - the signed-in user from the stored session, if `mybooks.db` has `merged:<that user>`;
  - otherwise the only `merged:` key if there's exactly one;
  - otherwise the database stays the no-account library. This refines the spec's "owner recorded last", because no
    order is recorded; with no clear owner it's safest that no account gets it.
- **The app opens directly on the last active library**, stored in the device settings as `library.active`, so a
  signed-in user never sees the no-account library flash at start. The session collector corrects it once the session
  is known.
- **The old library is closed after a switch** once the UI has rebuilt and its sync engine has stopped. The reader
  activity finishes itself when the active library changes.

## Review Focus

1. **Switching while the reader (a separate activity) or a background job still uses the old library.**
   - Expected: no crash. The reader closes, and the job finishes or skips.
   - Owner: Task 7, `readerClosesWhenTheLibraryChanges`; Task 4, `oldSpaceClosedOnlyAfterSyncStopped`.
2. **An account signing in for the first time on this phone while offline.**
   - Expected: its empty library opens, the offer still shows, and the merge or pull waits for the network. No crash,
     no lost books.
   - Owner: Task 5, `firstSignInOfflineKeepsOfferAndWaits`.
3. **Moving the phone's books into an account that already has the same ISBN** (the later "Add this phone's books").
   - Expected: merged into the existing book (`mergeFields`, notes and review joined as in the twin rule), never a
     unique-index crash or a duplicate.
   - Owner: Task 5, `addingPhoneBooksMergesSameIsbn`.
4. **An upgrade interrupted partway** (killed while copying).
   - Expected: the next start retries cleanly. Partial copies are removed first, and the original is never deleted
     before the copy checks out.
   - Owner: Task 6, `interruptedUpgradeRetriesFromTheOriginal`.
5. **Sign-out Remove followed by the same account signing in again.**
   - Expected: a fresh empty library that downloads from the cloud. Nothing from the removed library comes back from
     disk.
   - Owner: Task 4, `removeThenSignInAgainStartsEmptyAndPulls`.

---

### Task 1: Library ids, paths and per-library databases

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/LibraryId.kt`
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/LibraryFiles.kt`
- Modify:
  - `shared/src/androidMain/.../data/db/` (where `createAndroidDatabase` lives)
  - the iOS database factory in `shared/src/iosMain`
  - `androidApp/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`
- Test: `shared/src/commonTest/.../library/LibraryFilesTest.kt`, `shared/src/androidHostTest/.../library/LibraryDatabaseTest.kt`

**Interfaces:**
- **Produces:**
  ```kotlin
  sealed interface LibraryId { val key: String
      data object Local : LibraryId { override val key = "local" }
      data class Account(val userId: String) : LibraryId { override val key = userId } }
  fun libraryIdOf(key: String): LibraryId          // "local" → Local, else Account(key)
  class LibraryFiles(val fileSystem: FileSystem, val filesDir: Path) {
      fun coversDir(id: LibraryId): Path   // Local → filesDir/covers; Account → filesDir/libraries/<id>/covers
      fun ebooksDir(id: LibraryId): Path   // Local → filesDir/ebooks;  Account → filesDir/libraries/<id>/ebooks
      fun deleteAll(id: LibraryId)         // Account only: deletes filesDir/libraries/<id> recursively; Local: no-op
  }
  ```
- **Platform factories:**
  - `fun openLibraryDatabase(id: LibraryId): AppDatabase`. Local opens `mybooks.db`; Account opens
    `databases/libraries/<id>.db`. On Android pass the absolute path to Room, because a relative name with "/" is
    rejected.
  - `fun deleteLibraryDatabase(id: LibraryId)` (Account only: the db, -wal, -shm).
- **Backup rules:** add `<include domain="database" path="libraries" />` and `<include domain="file" path="libraries" />`.
  Covers only, matching today's choice to leave out ebooks: exclude `libraries/*/ebooks` if the rule syntax allows,
  otherwise accept including them. Record which in the commit message.

- [ ] **Step 1: Write failing tests:**
  - `localPathsAreTodays`.
  - `accountPathsAreUnderLibraries`.
  - `deleteAllRemovesOnlyThatAccount`: two accounts plus local; deleting one leaves the others.
  - `libraryIdRoundTrips`.
  - Android: `accountDatabaseOpensUnderLibrariesFolder`, which creates, writes and reopens the account database, and
    shows that `mybooks.db` is untouched.
- [ ] **Step 2: Run** `timeout 900 ./gradlew :shared:testAndroidHostTest --tests '*Library*' -q`. Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS. Then compile iOS.
- [ ] **Step 5: Commit:** "Libraries: ids, paths, per-library databases".

### Task 2: Per-library preferences

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/DbPreferences.kt`
- Modify: `data/PinnedTags.kt`, `HiddenSuggestions` (constructor takes `Preferences?` already; no API change)
- Test: `shared/src/commonTest/.../library/DbPreferencesTest.kt`

**Interfaces:**
- Consumes: `SyncStateDao` (`get`/`set`).
- **Produces:** `class DbPreferences(state: SyncStateDao, scope: CoroutineScope) : Preferences` with
  `suspend fun load()`. `get(key)` reads the cache, filled by `load()` from keys `pref:<key>`. `set(key, value)` updates
  the cache and writes `pref:<key>` on `scope`.
- `const val PER_LIBRARY_PREF_KEYS = setOf("home.pinnedTags", "suggestions.hidden")`.

- [ ] **Step 1: Write failing tests:**
  - `setThenGetAfterReload`: write, build a new instance, `load()`, then read.
  - `keysAreStoredUnderPrefPrefix`.
  - `pinnedTagsKeepsItsApiOnDbPreferences`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Libraries: per-library preferences".

### Task 3: `LibrarySpace` and `Libraries` (one library, no behaviour change)

**Files:**
- Create:
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/LibrarySpace.kt`
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/Libraries.kt`
- Modify: `AppContainer.kt`; `AndroidAppContainer.kt` and the iOS container factory (pass the factories from Task 1)
- Test: `shared/src/commonTest/.../library/LibrariesTest.kt`, plus the existing suites (must stay green)

**Interfaces:**
- Consumes: Task 1 `LibraryId`, `LibraryFiles`, the database factories; Task 2 `DbPreferences`.
- **Produces, `LibrarySpace`:**
  - `class LibrarySpace(val id: LibraryId, database: AppDatabase, files: LibraryFiles, device: DeviceServices, scope: CoroutineScope)`.
  - It holds what `AppContainer` holds per library today: `syncRecorder`, the `Syncing*` wrappers, `repository`,
    `quotes`, `challenges`, `ebooks`, `coverStore`, `ebookFiles`, `pinnedTags`, `hiddenSuggestions` (on
    `DbPreferences`), `sharing`/`sharingStatus` (Account only), `sync: LibrarySync?` (Account only), `suggestions`,
    `coverFinder`, `releaseAlerts`, `readingSummaries`.
  - Also `suspend fun open()` (loads prefs) and `suspend fun close()` (stops sync, then closes the database).
  - `DeviceServices` groups what a space needs from the device: http-backed services, `social`, `releases`, `events`,
    device `preferences`, the clock.
- **Produces, `Libraries`:**
  ```kotlin
  class Libraries(factory: suspend (LibraryId) -> LibrarySpace, deviceSettings: Preferences?, scope: CoroutineScope) {
      val active: StateFlow<LibrarySpace>
      suspend fun activate(id: LibraryId)     // opens the new space, swaps it in, then closes the old one
      suspend fun remove(id: LibraryId)       // Account only, not active: deletes its database and files
  }
  ```
  `deviceSettings["library.active"]` records the active key, and the app starts on it.
- **`AppContainer`:**
  - Exposes `libraries: Libraries`.
  - Its existing members (`repository`, `quotes`, `challenges`, `ebooks`, `coverStore`, `pinnedTags`,
    `hiddenSuggestions`, `sync`, `syncRecorder`, `suggestions`, `coverFinder`, `readingSummaries`, `releaseAlerts`,
    `sharingStatus`) become getters of `libraries.active.value`.
  - `readingSummaries` uses `flatMapLatest` over `active`.
- In this task the app still only ever activates `Local`, or the account the existing session collector starts sync
  for, exactly as now. That account still uses `mybooks.db` until Task 4, so behaviour doesn't change.

- [ ] **Step 1: Write failing tests:**
  - `activateSwapsAndClosesTheOld`: fake factory; the old space's `close()` is called after `active` changed.
  - `startsOnLastActiveKey`.
  - `removeRefusesActiveAndLocal`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.** Move the per-library wiring from `AppContainer` into `LibrarySpace`, unchanged in
  behaviour.
- [ ] **Step 4: Run the full gate.** Expected: green, with no other test changed except construction helpers.
- [ ] **Step 5: Commit:** "Libraries: one space per library, AppContainer delegates".

### Task 4: Switching on sign-in and sign-out

**Files:**
- Modify:
  - `AppContainer.kt` (session collector)
  - `LibrarySync.kt` (`signOut(keepLibrary)` no longer erases the shared database: Remove now means
    `libraries.remove(id)` after switching)
  - `ui/social/AccountViewModel.kt`
  - `ui/App.kt` (key and per-library `ViewModelStore`)
- Test:
  - `shared/src/commonTest/.../library/LibrarySwitchingTest.kt`
  - `androidApp/src/test/.../LibrarySwitchUiTest.kt` (Robolectric: no stale books after a switch)

**Interfaces:**
- Consumes: Task 3 `Libraries`, `LibrarySpace.sync`.
- **Session collector:**
  - `SignedIn(user)` → `libraries.activate(Account(user))`, then start that space's sync (first-sign-in hold: see
    Task 5).
  - A sign-out the user asked for (the latch) → `activate(Local)`, then on Remove `libraries.remove(Account(user))`.
  - Expiry → stay on the account space; it goes to `SignInAgain` as today.
- **`App.kt`:** `key(active.id.key) { CompositionLocalProvider(LocalViewModelStoreOwner provides spaceOwner) { MyBooksNavHost(...) } }`.
  `spaceOwner` is remembered per key, and its `ViewModelStore` is cleared on dispose.
- **Recording:** `SyncRecorder.enabled` is per space (Account spaces only, rule as today). The Local space never
  records or queues.

- [ ] **Step 1: Write failing tests:**
  - `otherAccountsBooksNeverReachMine` (the bug). Account B signs in and adds 2 books, then signs out with Keep;
    account A signs in. A's space has none of B's books, and the fake server for A has none either.
  - `signingBackInRestoresOwnLibraryOffline`. B signs in again offline: B's books are there, and its queued change is
    pushed when back online.
  - `keepShowsPhoneLibraryAndKeepsFiles`.
  - `removeDeletesOnlyThatAccount`.
  - `removeThenSignInAgainStartsEmptyAndPulls`.
  - `localLibraryNeverSyncsOrPublishes`.
  - `oldSpaceClosedOnlyAfterSyncStopped`.
  - `expiryKeepsAccountLibraryActive`.
  - UI test `switchShowsNoStaleBooks`: Home shows A's titles after the switch from B, and no B title remains in the
    semantics tree.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run** the new tests, then the full gate. Expected: green.
- [ ] **Step 5: Commit:** "Libraries: switch on sign-in and sign-out".

### Task 5: First sign-in offer and moving the phone's books

**Files:**
- Create:
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/LibraryTransfer.kt`
  - `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/social/AddPhoneBooksDialog.kt`
- Modify: `Libraries.kt` (offer state), `AccountScreen.kt`/`AccountViewModel.kt` (dialog, "Add this phone's books"),
  `AppContainer.kt`
- Test:
  - `shared/src/commonTest/.../library/LibraryTransferTest.kt`
  - `LibrariesOfferTest.kt`
  - `androidApp/src/test/.../ScreenshotTest.kt` (`addPhoneBooksLight`, `addPhoneBooksDark`)

**Interfaces:**
- Consumes: Task 3 spaces; Task 4 activation; the cloud-sync `findInLibrary` matching and `mergeFields`; the twin
  notes/review joining rule from `FirstMerge`.
- **Produces:**
  - `data class AddBooksOffer(val account: LibraryId.Account, val count: Int)`.
  - On `Libraries`: `val offer: StateFlow<AddBooksOffer?>`, `suspend fun addPhoneBooks()`, `fun notNow()`.
  - `class LibraryTransfer { suspend fun move(from: LibrarySpace, to: LibrarySpace): Int }`. It moves books with their
    quotes, ebook records, bookmarks, cover photos and ebook files, plus goals (a target's own goal for the same year
    wins).
    - Each source book is matched against the target with the `findInLibrary` rule (ISBN; title + first author only when
      a side lacks an ISBN). A match is combined with `mergeFields`, notes and review joined as in the twin rule, and
      its children rebound. Anything else is inserted through the target's wrappers (stamped and queued).
    - The source rows are deleted only after their target rows are written. It's idempotent: a book already in the
      target by uuid is skipped.
- **Hold:** an Account space whose `sync_state` has no `asked-local-books` key, while Local has live books, raises
  `offer` and holds its sync start until `addPhoneBooks()` or `notNow()`. Both set the key, then start sync. The
  first-sign-in merge sends whatever is in the account's library.

- [ ] **Step 1: Write failing tests:**
  - `addThemMovesEverything`: books, quotes, ebooks with their files, bookmarks, photos, goals. Local is empty
    afterwards.
  - `notNowLeavesThemAndPulls`.
  - `askedOncePerAccount`.
  - `addingPhoneBooksMergesSameIsbn`: target has ISBN X with notes "a", source has X with notes "b". The result is one
    book with "a" and "b" joined.
  - `interruptedMoveRepeatsSafely`: the fake target fails after 3 books; rerunning gives no duplicates and no losses.
  - `firstSignInOfflineKeepsOfferAndWaits`.
  - `laterAddFromSettingsMovesAndQueues`.
  - Screenshots with the exact copy ("Add this phone's 3 books to your account?", the sentence, "Add them", "Not now").
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run** the new tests and the full gate; look at the screenshots. Expected: green, and the dialog reads
  well in light and dark.
- [ ] **Step 5: Commit:** "Libraries: first sign-in offer and moving the phone's books".

### Task 6: Upgrade existing installs

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/library/LibraryUpgrade.kt`
- Modify: `AndroidAppContainer.kt` and the iOS container factory (run it before `Libraries` opens anything)
- Test: `shared/src/androidHostTest/.../library/LibraryUpgradeTest.kt` (real files and a real Room database)

**Interfaces:**
- Consumes: Task 1 paths and factories; Task 2 `DbPreferences`; the device `Preferences` (to read `home.pinnedTags`
  and `suggestions.hidden` and the stored session user).
- **Produces:**
  `class LibraryUpgrade(...) { suspend fun run(signedInUser: String?): UpgradeResult }`, with
  `sealed interface UpgradeResult { NothingToDo; MovedTo(account: String); StayedLocal; Failed(cause) }`.
- **Algorithm:**
  1. If `library.upgraded` is set in the device settings, do nothing.
  2. Open `mybooks.db`, read its `merged:` keys, and choose the owner by the plan decision. Close it.
  3. With no owner: copy the two per-library prefs from the device settings into Local's `DbPreferences`, then mark
     the upgrade done.
  4. With an owner:
     1. Remove any leftover target files.
     2. Copy the database (`.db`, `-wal`, `-shm`) to `databases/libraries/<owner>.db`, and `covers/` and `ebooks/` to
        `files/libraries/<owner>/`.
     3. Open the copy and check that its live book count equals the original's.
     4. Copy the two prefs into the copy's `DbPreferences`.
     5. Then delete the originals, create an empty `mybooks.db`, set `library.active` to the owner, and mark the
        upgrade done.
  5. On any failure: delete the partial copy, leave the originals, and return `Failed`. The app opens Local on the
     originals, and the upgrade is retried at the next start.

- [ ] **Step 1: Write failing tests:**
  - `syncedLibraryMovesToItsAccount`: books, covers, ebooks and prefs moved; local is new and empty; `library.active`
    is the owner.
  - `neverSyncedStaysLocal`.
  - `twoMergedKeysUsesSignedInUser`.
  - `twoMergedKeysNoSessionStaysLocal`.
  - `interruptedUpgradeRetriesFromTheOriginal`.
  - `failedCheckKeepsOriginals`.
  - `runsOnlyOnce`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit:** "Libraries: upgrade an existing library to its account".

### Task 7: Platform wiring and finishing touches

**Files:**
- Modify:
  - `androidApp/src/main/kotlin/com/mcosta/mybooks/MyBooksApp.kt` (connectivity follows the active space's sync;
    upgrade at start)
  - `androidReader/.../ReaderActivity.kt` (finish when the active library changes)
  - `androidApp/.../widget/ReadingWidget.kt` and `alerts/ReleaseAlertWorker.kt`, which use the active space; verify,
    and change only if they cache a space
  - `AccountScreen.kt` ("Couldn't open your library. Try signing in again." state)
  - `docs/social-setup.md` (a short note: libraries are per account on each phone)
- Test: `androidReader/src/test/...` `readerClosesWhenTheLibraryChanges`; `AccountViewModelTest` `openFailureShowsMessage`

**Interfaces:**
- Consumes: everything above.
- **Produces:** nothing new; it's wiring.

- [ ] **Step 1: Write failing tests:** `readerClosesWhenTheLibraryChanges`, `openFailureShowsMessage` (the factory
  throws for an Account; the app shows Local plus the message, and nothing is deleted).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run** the full gate, lint and iOS. Expected: green.
- [ ] **Step 5: Commit:** "Libraries: platform wiring".

## Verification

- The gate, lint and iOS commands in Global Constraints are all green.
- **Rollout with the user:**
  1. Back up each phone's database.
  2. Install on the user's phone: it upgrades to the account library and nothing visible changes.
  3. On the second phone (after Remove there), Carla signs in: her library downloads.
  4. Repeat the cross-account test: neither account sees the other's books.
