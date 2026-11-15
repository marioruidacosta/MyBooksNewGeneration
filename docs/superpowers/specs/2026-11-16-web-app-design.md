# MyBooks on the web: design

**Date:** 2026-11-16
**Status:** reviewed (builder check); open question answered
**Builds on:**
- `2026-11-08-cloud-sync-design.md`, which defines the cloud library this site reads and writes.
- `2026-11-09-separate-libraries-design.md`, which puts one library per account.

## Intent
The person manages their library **at a desk**, in a browser, signed in with the same account as their phones. Phones are good for one book at a time. The desk is for seeing many books at once, sorting and filtering them, changing several together, and adding or cleaning up in bulk.

Every change made on the site reaches the phones, and every change made on a phone reaches the site, through the cloud sync that already exists.

## Goals (all five agreed in conversation)
1. **Library table.** Every book in the account's library, with sortable and filterable columns:
   - title, authors, status, score, tags, series and number, date started, date read, publisher, year, pages, lent to, date added;
   - a search box over title, author and ISBN, ignoring accents and case, like the app;
   - multi-select with bulk actions: set status, add or remove a tag, set series, mark lent or returned, delete.
2. **Book page and editor.** Every field the app stores:
   - notes and review;
   - quotes (add, edit, delete);
   - reading dates and progress;
   - the cover: upload an image file, paste an image link, or remove it.
3. **Fast adding.**
   - Type an ISBN or a title and pick a result from Google Books, Open Library or the National Library (BNP).
   - Paste a list of ISBNs, see each one looked up, and add the found ones in one go.
4. **Import and export** of the Goodreads CSV and the app's own backup file, with the same formats as the app.
5. **Tidying.** Lists of books with no cover, no page count or no ISBN, plus likely duplicates, each fixable in a few clicks.

## Non-goals (the phone stays the place for these)
- Community feed, friends and profiles.
- New in Portugal.
- Suggestions and BookNerd.
- The ebook reader and ebook files.
- Year in review and reading-activity charts.
- The home-screen widget and notifications.
- Barcode scanning and the camera.

## Anti-goals (a failure even if it works)
- The site must never lose or overwrite a phone's newer change. It uses the same newest-change-per-field rule, through the same server functions.
- No paid services and no new accounts: GitHub Pages and Supabase's free plan only.
- No server secrets in the browser. Only the public (anon) key, which is safe by design with row-level security.

## Constraints and what exists
- **Supabase project:** the existing one. Its URL and anon key are already in `local.properties` and CI.
- **Sign-in is the app's:** an email address, then the 6-digit code sent by email (Supabase email OTP, `signInWith(OTP)` and then `verifyEmailOtp`). There are no redirect URLs to configure.
- **The library lives in the private tables:** `library_books`, `library_quotes`, `library_challenges`, `library_ebooks`, `library_bookmarks` and `library_prefs`. The columns are listed in `supabase/migrations/2026111000_library_sync.sql`.
  - Rows are keyed by `(owner, id)`, where `id` is the book's uuid, or the year for challenges.
  - Each row carries `field_times` (ms per field), `deleted` and `seq`.
- **Writes go only through `sync_push(changes)`.**
  - Each change is `{table, id, fields, times, deleted?}`.
  - The server merges per field, keeping the newest time, and caps times at now + 1 min.
- **Reads:**
  - `sync_pull(after, max)`, paged by `seq`.
  - Realtime broadcast `library:<owner>` pings after every write.
- **Cover photos** are in the private bucket `covers` at `<owner>/<book uuid>.jpg`. The book's `cover_url` is the marker `photo`. Web covers are plain URLs.
- **The app is the reference for behaviour,** in `shared/src/commonMain`:
  - `SyncCodec.kt`: field names and values;
  - `mergeFields`: the per-field rule;
  - `GoodreadsCsv`, `BackupSerializer`;
  - `findInLibrary` and `LibraryIndex`: the duplicate rule;
  - `authorKey` and the library spelling of authors;
  - `BnpClient`, `OpenLibraryClient`, `GoogleBooks*`;
  - `catalogTitleKey`, `searchKey`.
- **Desktop browsers:** current Chrome, Edge, Firefox and Safari. It must be usable down to a 1024 px window. Phone-width layout is not a goal.
- **The app's copy and wording are in English;** the site matches.

## Approaches (chosen: B)
- **A. Compose for Web, reusing the app's Kotlin.**
  - Some logic would be shared.
  - Room doesn't run in browsers, so the data layer would be rebuilt anyway.
  - The UI is phone-shaped: no real tables or multi-select, poor text selection.
  - About 10 MB to download before anything shows.
  - Works against desk use.
- **B. A separate React web app acting as one more synced device (chosen).**
  - A proper desk UI that loads fast.
  - The cost: the sync rules, lookups and imports are rewritten in TypeScript. Tests check them against the app's behaviour.
- **C. Next.js server-rendered on Vercel.** Like B, plus a server to run, with no gain for a personal tool.

## The design

### Where it lives and how it ships
- **Location:** a `web/` folder in this repository.
- **Stack:** Vite, React and TypeScript, with `@supabase/supabase-js`, TanStack Table, TanStack Router and Vitest with React Testing Library.
- **Deploying:** a GitHub Actions workflow `web.yml` tests and builds on every PR. On `main` it deploys to GitHub Pages at `https://marioruidacosta.github.io/MyBooksNewGeneration/` (the repository is public, so Pages is free). The Supabase URL and anon key are repository variables.
- **The lookup helper:** one Supabase Edge Function, `lookup` (Deno), in `supabase/functions/lookup`. It handles the two sources the browser can't call well itself; Open Library is called straight from the browser.
  - **BNP:** `?source=bnp&isbn=` fetches BNP's Dublin Core record and returns JSON with the fields `BnpClient` reads. The browser can't call BNP directly because of CORS.
  - **Google Books:** `?source=google&q=` calls the API with the app's `GOOGLE_BOOKS_API_KEY`, kept as a Supabase secret. Anonymous calls share a daily quota that is usually exhausted, and pasting a list of ISBNs would hit it.
  - It's open to signed-in users only (it checks the JWT).
  - It has Deno unit tests against recorded responses. *(My call, from the review.)*
  - "Paste ISBNs" looks up at most 4 at a time.

### Data on the site
- **Loading:**
  - After sign-in, the site loads the whole library with `sync_pull` from 0, page by page.
  - It keeps it in memory and in IndexedDB, so the next visit opens at once and then pulls from the last `seq`.
  - A 5,000-book library is about 20 MB of JSON, which is fine.
- **Live updates:**
  - It joins `library:<owner>` and pulls on every ping, on window focus, and every 60 s while visible.
  - Rows pulled are merged with the app's rule: newest time per field wins, and a deletion wins over an older edit.
- **Writing:**
  - Every edit sets the changed fields' times to `Date.now()` and updates the screen at once.
  - The change is queued in IndexedDB and sent with `sync_push` in batches of up to 100 (the server takes up to 200).
  - Tags travel as the app's single text column, joined with its `TAG_SEPARATOR`.
  - Set-aside changes are shown ("Couldn't save: …") and dropped from the queue.
  - Network errors retry at 5 s, 15 s, 1 min, then 5 min, and immediately when the browser comes back online.
  - A badge shows "Saved", "Saving…", or "Offline · N changes waiting".
- **New books:**
  - The site creates a uuid and sets `date_added = now`.
  - If the library already has the same ISBN, or the same title and first author (`findInLibrary` with `authorKey`), adding opens that book instead of making a duplicate.
- **Deleting** sends `deleted = true`, as the app does. The row disappears from every list.
- **The site never runs `merge_library`.** It only ever shows and edits what is in the cloud. A phone that hasn't done its first sync shows nothing on the site until it does. The page explains this when the library is empty.
- **Authors:** a book added on the site takes the library's spelling of an author it already has, as in the app (`authorKey`).

### Covers
- **Uploading an image** (or dragging it onto the book):
  - It is shrunk in the browser to 1000 px on the long side, as a JPEG under the bucket's 2 MB limit (canvas).
  - It is uploaded to `covers/<owner>/<uuid>.jpg` (upsert).
  - The book gets `cover_url = "photo"`, and its time is set again on **every** upload: phones fetch a new photo when that field's time moves.
- **Pasting a link** sets `cover_url` to that URL.
- **Removing** clears `cover_url`. The photo object is left in the bucket, as the app does.
- **Shown:** photos through signed URLs (1 h, cached), and web covers directly. A missing cover shows the generated cover (the same colours as the app, from `coverColorIndex`).

### The pages
- **Sign in:** an email box and "Send code", then a box for the 6-digit code, then the library. A "Sign out" link is in the header.
- **Library:**
  - The table described in Goals, with column choice remembered per browser.
  - A row opens the book page. Checkboxes select rows, and the bulk-action bar appears when any are selected.
  - Keyboard: `/` focuses the search box, `n` adds a book, `Delete` deletes the selection after confirming.
- **Book page:**
  - All fields as a form, saved field by field when focus leaves the field.
  - Status and score as in the app.
  - Quotes list.
  - Cover panel.
  - "Ebook on a phone" is shown read-only when the book has an ebook record.
- **Add:**
  - A search box: an ISBN goes to BNP, then Open Library, then Google Books, in the app's `LookupChain` order. A title goes to Google Books and Open Library search.
  - A "Paste ISBNs" area. Each line is looked up and shown with its result, already in the library, or not found. "Add N books" adds the found ones with status To read.
- **Import and export:**
  - Goodreads CSV import, with a preview: N new, M already in the library (merged with the app's rule), K skipped. Export too.
  - Backup JSON or ZIP import and export, in the app's format.
- **Tidy:**
  - Tabs: Missing cover, Missing pages, Missing ISBN, Possible duplicates (the same `catalogTitleKey` and `authorKey`).
  - Each row has inline fixes: look up the missing data, upload a cover, merge two books.
  - Merging keeps one book, combines the fields with the app's twin rule (newest field wins; notes and review joined with a blank line, as `keepBothTexts` in `LibraryTransfer.kt`), moves the other's quotes to it, then deletes the other.
- **Goal:** the year's reading goal can be set from the library header ("2026 goal: 25"). This is the `library_challenges` row.

### What the site does about the community copy (decided: like the phone)
Edits made on the site publish to the community exactly as the phone does:
- **Same rules as the app:** the account's share setting, books excluded from sharing, and which changes publish (status, score and review).
- **Same writes as the phone:** `share/ShareTracker.kt` and `share/Publisher.kt`, against the existing community RPCs in the social and feed migrations.
- **One public id per book:** the book's uuid, as with the phones. Changes the site *pulls* from a phone are never published by the site, because the phone that made them already did.
- **The book page shows whether the book is shared** (see "Which books count as shared" below). Excluding a book stays on the phone.
- **Backup import carries** each book's share id and excluded flag, as the app's importer does.
- **Tests:** parity with `PublisherTest` and `ShareTracker` behaviour. A change to a shared book publishes exactly once; an excluded book never publishes; a pulled change doesn't publish.

## Testing
- **Parity with the app.**
  - Fixtures generated from the Kotlin code (`SyncCodecTest`, `GoodreadsCsv` and backup samples) are checked into `web/test/fixtures`.
  - The TypeScript codec, merge, Goodreads and backup code must produce the same outputs.
  - Covered: per-field merge, deletion versus edit, capped times, the duplicate rule, author spelling.
- **Components** (React Testing Library, with supabase-js mocked):
  - table sorting, filtering and bulk actions;
  - editing a field queues the right change;
  - adding a duplicate opens the existing book;
  - the paste-ISBNs flow;
  - the import preview;
  - the tidy merge.
- **The edge function:** a Deno test against a recorded BNP response, plus not-found and error cases.
- **Manual end-to-end check:** before release, sign in on the site and on the phone. Edit on each, check that the other shows the change within seconds, and check that a conflicting edit resolves to the newest.

## Rollout
1. Merge the open PRs (#24–#28). The site relies on the sync tables, which are already live in Supabase.
2. Deploy the edge function and enable GitHub Pages (Source: GitHub Actions). Add the repository variables.
3. Open the site, sign in with the same email, and check the library matches the phone.

## Decisions after review
- **Community copy:** the site publishes like the phone. Decided by the person.
- **Lookups:** BNP and Google Books go through the `lookup` Edge Function, with the Google key as a Supabase secret. Open Library is called directly. *(My call.)*
- **Covers:** `cover_url`'s time is set again on every upload, so phones fetch the new photo. *(From the review; the app works this way.)*
- **Which books count as shared** *(found while planning; my call)*:
  - The phones keep "Don't share this book" on the device, and it isn't synced. Excluded books have no public copy.
  - The site can't read public copies directly, so it gets a small read-only server function, `my_shared_book_ids()`, which returns the caller's shared book ids.
  - The site publishes changes only for those books, and for books it adds itself.
  - The book page **shows** "Shared with friends" or "Not shared" but has **no switch**. A switch on the site would last only until a phone shared the book again.
  - This is the one server addition besides the `lookup` function. It is applied only with the person's OK.

## Left to the builder
- Component structure.
- Styling, within the app's palette (colour tokens taken from `MyBooksTheme`, plus light and dark mode following the system).
- Exact table interactions.
- The IndexedDB library.
- Error copy, beyond what's quoted here.
