# Separate libraries per account: design

**Date:** 2026-11-09
**Status:** approved in conversation; spec under review
**Builds on:** `2026-11-08-cloud-sync-design.md` (cloud library sync, on `feature/cloud-sync`).

## Why

The bug, found in testing on two phones:
1. Account B was signed in on a phone.
2. B signed out with "Keep".
3. Account A signed in on the same phone.
4. The first sign-in merge treated B's library on the phone as A's and merged it into A's account.

The one affected account was repaired by hand. This design makes it impossible: a phone keeps each account's library
apart, and nothing is ever merged or erased across accounts.

## Decisions (from the conversation)

| Topic | Decision |
|---|---|
| Model | A phone keeps a separate library for each account that signed in on it, plus its own no-account library. |
| No-account library at an account's first sign-in | Ask once: "Add this phone's N books to your account?" Yes moves them into the account. No keeps them in the no-account library. |
| While nobody is signed in | The phone shows its no-account library and works as it does today. |
| Storage | One database per library, with its own cover and ebook folders (approach A). |

## Libraries on the phone

| Library | Database | Cover photos | Ebook files |
|---|---|---|---|
| No-account | `databases/mybooks.db` (as today) | `files/covers/` (as today) | `files/ebooks/` (as today) |
| Account `<id>` | `databases/libraries/<id>.db` | `files/libraries/<id>/covers/` | `files/libraries/<id>/ebooks/` |

**Each library contains:**
- books, quotes and highlights, reading goals;
- ebook records and bookmarks;
- the sync queue and sync state;
- the community share records;
- the two synced preferences: pinned tags (`home.pinnedTags`) and "Not interested" (`suggestions.hidden`). These move from
  the device settings into the library's own `sync_state` / a per-library preferences store.

**Device-wide (not per library):**
- appearance, notification settings, the downloaded New in Portugal lists;
- the community sign-in session, and the library sort order and grid/list choice.

## Switching

- A new `Libraries` component holds the active library: `StateFlow<ActiveLibrary>`, where `ActiveLibrary` is either
  `Local` or `Account(id)`. It opens the library's database, repository, DAO wrappers, `SyncRecorder`, `CoverStore`,
  `EbookFiles`, `ShareTracker` and preferences, as one `LibrarySpace` per active library.
- **Signing in** activates `Account(id)`, creating an empty library if the account has none on this phone.
- **Signing out** with Keep or Remove activates `Local`.
- **The app reloads on a switch.** The UI is keyed on the active library's id, so every screen and view model is rebuilt
  against the new `LibrarySpace`; view models created for the previous library are cleared. Nothing from the previous
  library stays on screen or in memory.
- **The previous library is closed after the switch.** Its database closes once its sync engine has stopped and no screen
  uses it.
- **Sync runs only for account libraries:**
  - the sync engine (`LibrarySync`) is built per `LibrarySpace` on that library's raw DAOs;
  - switching stops the old engine, whose queue stays in its own database, and starts the new one;
  - the no-account library never syncs or publishes.

## First sign-in of an account on this phone

When an account signs in and has no library on this phone:
1. Create its empty library.
2. If the no-account library has live books, ask once:
   - Title: "Add this phone's N books to your account?"
   - Text: "They'll move into your account's library and sync to your other devices."
   - Buttons: "Add them" and "Not now".
3. **Add them:** move the books into the account's library, with their quotes, ebook records, bookmarks, cover photos,
   ebook files and goals. The no-account library is left empty. The existing first-sign-in merge then runs, matching
   the books against the account's cloud library.
4. **Not now:** the books stay in the no-account library. The account's library downloads from the cloud (the empty
   path of the first merge).
5. The choice is stored in the account's library, so the question is never asked again for that account on this phone.
   The no-account books can still be added later from Settings → Account ("Add this phone's books").

An account's library is never merged with another account's library. The first-sign-in merge only ever sends that
account's own library, or no-account books the person chose to add.

## Signing out

- **Keep:** the account's library stays on the phone, hidden, and the next sign-in of that account switches to it
  instantly, even offline. Unsent changes stay in its queue and are sent at that sign-in.
- **Remove:** the account's database and folders are deleted, and nothing else is touched. The existing warnings about
  unsynced changes and ebook files still apply.
- After either, the phone shows the no-account library.

## Upgrading existing installs

Run once, at the first start of this version, before anything opens `mybooks.db`.

- If `mybooks.db`'s `sync_state` has a `merged:<owner>` key, the library belongs to that account:
  1. Copy the database (with `-wal`/`-shm`) to `databases/libraries/<owner>.db`.
  2. Copy `covers/` and `ebooks/` into `files/libraries/<owner>/`.
  3. Open and check the copy (it opens and has the same book count).
  4. Only then delete the originals, and create an empty no-account `mybooks.db`.
- If more than one `merged:` key exists, the owner recorded last wins. Their library is the one the phone was using.
- If there is no `merged:` key, `mybooks.db` stays the no-account library.
- On any failure, the originals stay untouched and the app opens them as the no-account library. The upgrade is
  retried at the next start.

## Errors

| Situation | What happens |
|---|---|
| An account library can't be opened | Show the no-account library and "Couldn't open your library. Try signing in again." Nothing is deleted. |
| Moving the no-account books fails partway | The move runs as copy, then check, then delete in one transaction per table, so an interrupted move repeats safely. Nothing is lost or duplicated. |
| Sign-out Remove while another library is active | Can't happen: Remove only ever deletes the library it signed out from. |

## The rest of the app

Everything that reads the library uses the active `LibrarySpace`:
- backup export and import, Goodreads import and export;
- the book page and the ebook reader (`androidReader` opens files from the active library's `EbookFiles`);
- suggestions, BookNerd, the reading widget, and the monthly New in Portugal alerts (which use whichever library is
  active when they run).

Nothing reads two libraries at once.

**Android backup:** `backup_rules.xml` and `data_extraction_rules.xml` currently include only `mybooks.db` (and
`-wal`/`-shm`) and `covers`. Add the `libraries` folder in the database domain and `libraries/` (covers only, not ebooks,
matching today) in the file domain.

## Testing

- **The bug:**
  - Account B signs in, adds books, then signs out with Keep. Account A signs in. A's library and A's cloud (the fake
    server) contain none of B's books.
  - B signs in again and gets its own library back, offline too, with its unsent changes then pushed.
- **First sign-in question:**
  - "Add them" moves every book, quote, ebook record, bookmark, photo, file and goal into the account, and the merge
    runs.
  - "Not now" leaves them, and the account library downloads.
  - Asked once per account per phone.
  - "Add this phone's books" later moves them.
- **Signing out:** Keep shows the no-account library and the account's files stay. Remove deletes only that account's
  files.
- **Upgrade:**
  - A synced `mybooks.db` becomes its account's library, with covers and ebooks moved.
  - A never-synced one stays the no-account library.
  - A failure partway leaves the originals and the app still opens.
  - Two `merged:` keys: the most recent owner.
- **Isolation:**
  - After a switch, no screen or view model shows the previous library's books.
  - Covers, ebooks, share records, preferences and the sync queue follow their own library.
  - The no-account library never syncs or publishes.
- **Existing tests:** all current tests run against a single `LibrarySpace` and stay green.
- **Screenshots:** the "Add this phone's N books to your account?" question, in light and dark.

## Rollout

1. Back up each phone's database before installing.
2. Install on your phone. Your library becomes your account's library, and nothing visible changes.
3. On the second phone (after "Remove" there), sign in as Carla. Her library downloads from her account.
4. Repeat the test that found the bug. Neither account sees the other's books.

There are no server changes.

## Out of scope

- Several accounts signed in at once, or an account switcher.
- Moving books between two accounts' libraries.
- Deleting another account's library on this phone without signing in as that account.
