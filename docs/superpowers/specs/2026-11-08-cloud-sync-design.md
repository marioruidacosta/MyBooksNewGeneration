# Cloud library sync: design

**Date:** 2026-11-08
**Status:** approved in conversation; spec under review
**Part of:** multi-device MyBooks. This spec covers sync only. The web app gets its own spec later and will read and
write the tables defined here.

## Goal

Install MyBooks on several devices, sign in with the same account on each, and see the same library on all of them,
offline included. Without signing in, the app works exactly as today, on one device.

## Constraints

- **Free:** Supabase free plan only (500 MB database, 1 GB file storage). No new paid services.
- **Offline first:** every device works offline. Changes made offline are sent once the device is back online.
- **Room stays** the database on the phone.
- **The community stays as it is:** profiles, friends and the public `shared_books` copy keep working. The public copy
  keeps following the sharing setting.
- **No change for signed-out users:** sync is used only while signed in.

## Decisions (from the conversation)

| Topic | Decision |
|---|---|
| Approach | Our own sync on Supabase: private tables, per-field merge in a server function, a change queue on each device, and Realtime pings. |
| Files | Cover photos you took sync. Ebook files don't, but their reading position, bookmarks and highlights do. |
| Conflicts | Newest change per field wins. |
| First sign-in with books on the device | Merge with the cloud library, matching duplicates by ISBN, or by title and first author. |
| Timing | Changes upload right away. Open devices receive them live, via a Realtime ping then a pull. |
| Sign out | Ask "Keep a copy of your library on this device?", defaulting to Keep. |

## What syncs

**Synced with the account:**
- **Books:** every field the app stores, including:
  - ISBN, title, authors, publisher, year, pages, cover URL, description;
  - status, rating, notes, review;
  - date added, started and read; current page, progress;
  - tags, series and number, lent to and on.
- **Quotes and highlights:** text, page, position, date added.
- **Reading challenges:** year and goal.
- **Ebook state:** each book's ebook record (format, file name, size, date added) and reading position (position, last opened).
  Bookmarks too: position, label, date added.
- **Cover photos you took:** the image file, in Supabase Storage.
- **Account-wide preferences:** pinned tags (`home.pinnedTags`) and the "Not interested" suggestions list.

**Kept on each device only:** sort order, grid or list, appearance, notifications, the downloaded New in Portugal lists,
ebook files, and the cover cache of web covers.

## Supabase: tables

Private tables, one per kind:
- `library_books`
- `library_quotes`
- `library_challenges`
- `library_ebooks`
- `library_bookmarks`
- `library_prefs`

Every row has:
- `id uuid`: the same on every device. Challenges are keyed by `(owner, year)`; preferences by `(owner, key)`.
- `owner uuid`: references `auth.users`.
- The synced fields, as columns.
- `field_times jsonb`: field name → last-change time in milliseconds, set by the server (see Merging).
- `deleted boolean`: a deletion marker kept so deletions reach other devices.
- `seq bigint`: from one sequence shared by all `library_*` tables, set on every insert or update. `sync_pull` uses it as
  its cursor.

Quotes, ebooks and bookmarks refer to their book by the book's `uuid`.

Access rules (RLS): each table allows `select` only where `owner = auth.uid()`. Writes go only through the functions
below, which are `security definer` with `require_user()` and `set search_path = public, pg_temp`. Anonymous users have
no access.

Storage: a private bucket `covers`, path `<owner>/<book uuid>.jpg`. Its policy allows reading and writing only your own
folder.

The public `shared_books` copy is untouched. It keeps being produced from the library on the phone, exactly as now.

## Supabase: functions

- **`sync_push(changes jsonb) → jsonb`**
  - Input: a list of changes. Each change is `{table, id, fields: {name: value}, times: {name: ms}, deleted?}`.
  - For each field, the incoming value replaces the stored one only when its time is newer.
  - An incoming time is capped at the server's `now()` plus 1 minute, so a device whose clock runs ahead can't keep
    winning.
  - On equal times, the stored value stays.
  - Deletion is a field (`deleted`) with its own time: a deletion newer than an edit wins, and only an explicit
    undelete (`deleted = false` newer than the deletion) brings the row back; ordinary edits don't revive a deleted row.
  - Returns the ids applied and the ids set aside with a reason (e.g. a value too long or wrongly typed).
- **`sync_pull(after bigint, max int default 500) → jsonb`**
  - Returns the caller's rows with `seq > after`, in `seq` order, up to `max`, plus the last `seq`.
  - Deleted rows are included, so devices can apply the deletion.
- **`merge_library(books jsonb) → jsonb`**: the first sync of a device that already has books.
  - Each incoming book is matched to a cloud book by ISBN, or else by title (before any subtitle) and first author, using
    the same rule as `findInLibrary`.
  - A match is merged per field with the newest time and keeps the cloud book's `uuid`. A book with no match is inserted.
  - Returns the local-to-cloud id map. The device then sends its quotes, ebook state and bookmarks against the cloud
    `uuid`s through `sync_push`.
- **Realtime:** after every write, the functions send a broadcast on the private channel `library:<owner>` with no data. A
  device that receives it calls `sync_pull`. Only the owner may join their channel.

## The phone

**Database v8** (a Room migration from v7):
- `books`, `quotes`, `ebooks` and `bookmarks` gain:
  - `uuid` (filled for existing rows during the migration);
  - `fieldTimes` (JSON);
  - `deleted` (rows marked deleted are hidden from every screen and query).
- `challenges` gains `fieldTimes` and `deleted`.
- New table `sync_queue`: id, table, row uuid, changed fields, created at.
- New table `sync_state` (key/value): the account it belongs to, the last `seq` pulled, whether the first merge is done.
- The migration keeps every existing row unchanged.

**Recording changes:** every write already goes through the repositories (`BookRepository`, `QuoteRepository`,
`ChallengeRepository`, `EbookRepository`, `PinnedTags`, `HiddenSuggestions`). Each write:
- sets the changed fields' times to now;
- adds a `sync_queue` entry in the same database transaction.

A deletion sets `deleted` (with its time) instead of removing the row. Rows are removed for good only after the server has
confirmed the deletion.

**The sync engine:** a `LibrarySync` class in `shared/commonMain`, started by `AppContainer` while signed in.
- **Push:** it sends the queue in batches to `sync_push` and removes what the server confirms. Set-aside changes leave the
  queue and are counted in the status.
- **Pull:** it calls `sync_pull` from the stored `seq`, applies the rows per field with the same rule as the server, and
  stores the new `seq`. It runs:
  - on start;
  - when the app comes to the foreground;
  - after a push;
  - on a Realtime ping;
  - on pull-to-refresh, in the library and on Home.
- **Retry:** after a failure it waits 5 s, 15 s, 1 min, then 5 min, and retries straight away when the connection returns.
- **Covers:**
  - A cover photo saved on a device is uploaded after its book has synced.
  - A book whose cover is in the bucket is downloaded into `CoverStore` the first time it is shown, then kept.
- **Ebooks:** a book whose ebook record came from another device, and whose file isn't on this one, shows
  "Ebook on another device" in its Ebook card. Adding the file here links it to the same record.

**First sign-in on a device:**
- No local books: pull everything.
- Local books: show "Combining your libraries…" with a count and call `merge_library` in batches of 100. Then push the
  related quotes, ebook state, bookmarks, challenges and preferences, and pull the merged result.
- Runs once per device per account. If interrupted, it resumes where it stopped.

**Signing out:**
- A dialog asks "Keep a copy of your library on this device?", with buttons "Keep" (the default) and "Remove".
- Keep: rows stay as a local library. The queue and `sync_state` are cleared.
- Remove: all library tables and the local cover photos are erased.
- The cloud library is untouched either way.
- Signing in again later, to the same or another account, runs the first sign-in merge.

**Status** (Settings → Account): "Synced just now / 5 minutes ago", "Syncing…", "Offline · N changes waiting",
"Couldn't sync · Retry", "Sign in again to sync", "Cover photos not syncing: storage full".

## The public copy with several devices

Today each phone gives a shared book its own random id (`share_state.shareId`, the `shared_books.id`). With two devices
that would mean two public copies of the same book, and two feed entries for every change. So:

- **One id per book.** The book's sync `uuid` is also its public id.
  - In the v8 migration, a book already shared keeps its existing `shareId` as its `uuid`. Its public copy, likes and
    feed history stay attached.
  - Other books get a new `uuid`.
  - `merge_library` keeps the cloud book's `uuid`. A local book merged into it moves its share state to that `uuid`. If
    both had public copies, the local one is deleted through the existing unshare path.
- **Only the device where you made a change publishes it.** Changes arriving through `sync_pull` update the library but
  never publish to `shared_books` or record feed activity. That device's own share state is updated to match, so it
  won't republish the same change later.

## Errors and limits

| Situation | What happens |
|---|---|
| No network or a timeout | The queue keeps the changes and retries as above. The app never blocks. |
| Session expired | The status says "Sign in again to sync". The queue is kept. |
| A change refused by the server | It's set aside and counted in the status. The rest goes through. The device keeps its value. |
| A cover upload fails | It's retried later, separately. Book data isn't delayed. |
| Storage full (1 GB) | New cover uploads stop, the status says so, and data keeps syncing. |
| The device clock runs ahead | The server caps incoming times at now plus 1 minute. |

Sizes: about 4 KB per book with notes, so a 5,000-book library is around 20 MB of the 500 MB. Cover photos are about
100 KB, so the 1 GB holds around 10,000 of them.

## Rollout

1. Apply the migration (via the SQL Editor, as before, recorded in the migration history).
2. Back up the phone's database, then install the build with database v8.
3. Sign in. The phone's library is merged into the empty cloud library.
4. Install the app on the other devices and sign in with the same account.

## Testing

**Server (pgTAP, in CI):**
- The newest time per field wins.
- An equal time keeps the stored value.
- A far-ahead clock is capped.
- A deletion beats older edits, and a newer edit undoes the deletion.
- `merge_library` matches by ISBN, and by title and first author, and keeps the cloud `uuid`.
- `sync_pull` returns only the caller's rows after `after`, in `seq` order, deleted rows included.
- Anonymous callers are refused.
- Another user's rows can't be read or written.

**App (unit tests):**
- Each repository write records field times and a queue entry in the same transaction.
- The queue survives a restart and is sent once online.
- Set-aside changes don't block the queue.
- Pulled rows merge per field.
- Two simulated devices sharing a fake server end up with the same library after offline edits on both.
- A change pulled from another device doesn't publish to `shared_books` or the feed. A book shared before v8 keeps its public id as its `uuid`.
- First sign-in merge, including resuming after an interruption.
- Sign out with Keep and with Remove.
- Cover upload and download.
- The Room v7 → v8 migration keeps every row and gives each a `uuid`.

**Screens:**
- The sync status in Settings, in each state.
- "Combining your libraries…".
- The sign-out dialog.
- "Ebook on another device".

## Out of scope

- The web app (next spec).
- Syncing ebook files.
- Sharing a library between different accounts.
- Version history and undo across devices.
- Syncing device-only settings.
