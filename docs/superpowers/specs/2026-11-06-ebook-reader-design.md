# Ebook reader — design

Date: 2026-11-06 · Status: agreed in conversation, awaiting review of this document

## Goal

Read DRM-free EPUB and PDF books inside MyBooks, with the reading position, progress, highlights and bookmarks
kept with the book in the library. Android first; the iPhone app keeps the data but has no reader yet.

Decided with the user:

- Platforms: **Android only** in this project.
- Getting files in: **pick a file** on the phone, **"Open with MyBooks"** from other apps, and **free classics**
  searched and downloaded from Project Gutenberg in the app.
- Storage: files are **copied into the app** and **left out of every backup**; positions, bookmarks and highlights
  are backed up.
- Reader features: page turning, position and progress saved by themselves, **highlights as quotes**, **look and
  feel** (font, size, line spacing, light/sepia/dark), **contents and bookmarks**, **search** and **read aloud**.
- Engine: the **Readium Kotlin toolkit 3.4** (BSD-3). EPUB gets every feature above. PDF (Readium's PDFium
  adapter) gets page turning, contents, bookmarks and position only: Readium has no search, highlights,
  read-aloud or text selection for PDF.
- Adobe DRM (Adobe Digital Editions, `.acsm`): **not opened** (Adobe's RMSDK is licensed only to companies;
  removing DRM is circumvention). Such files are recognised, explained, and the book can be added without its
  file. Readium LCP is out of scope (needs an EDRLab licence).

## What the user sees

### Getting books in

- **Home › +** gets two items: **Add an ebook** (system file picker, EPUB or PDF) and **Free classics**.
- **Open with MyBooks**: opening or sharing an EPUB, PDF or `.acsm` file from Files, Gmail, a browser etc. offers
  MyBooks. A sheet shows the book the file was matched to, or the new book it will create (title, authors, cover
  from the file when it has one), with **Read now** and **Add to To read**.
- **Matching**: an ISBN in the file's metadata matches a library book with that ISBN; otherwise title and first
  author match like Goodreads import does (`findWithoutIsbn`); otherwise a new book is created (status To read,
  or Reading with Read now). A book that already has an ebook file asks before replacing it.
- **Free classics**: a search box over Project Gutenberg (Gutendex API, `https://gutendex.com/books/?search=`),
  results with title, author, language and cover; **Download** adds the book (To read) with its EPUB, then
  **Read**. Searching can take 15 s or more: the screen says "Searching Project Gutenberg…"; offline or failed
  searches say so with Retry. Only EPUB downloads (`application/epub+zip`) are offered.

### Protected files

An `.acsm` file, or an EPUB/PDF with Adobe DRM (EPUB: `META-INF/rights.xml`, or `META-INF/encryption.xml` naming an
Adobe algorithm; PDF: an `/Encrypt` dictionary with the Adobe `/Filter /EBX_HANDLER` or `ADBE.*`), shows:
"This book is protected with Adobe DRM. Read it in Adobe Digital Editions; MyBooks can still track it." with
**Add to library without the file** (matching as above; for `.acsm`, the title and authors in its XML). Any other
encrypted file gets "This file is protected and can't be opened" with the same choice.

### Book page

- A book with an ebook file: a **Read** button under the title, and an **Ebook** card (EPUB or PDF, size, date
  added, **Remove file**, which deletes the file and keeps bookmarks and highlights).
- Without a file: **Add ebook file** in the Ebook card.
- After a backup is restored on another phone: "Ebook file not on this phone" with **Add it again** (positions,
  bookmarks and highlights come back with the file).

### Reader

- Full screen; a tap in the middle shows or hides the bars; swipe or tap the side edges to turn pages.
- Top bar: back, **Contents**, **Bookmarks** (add here, list, delete), **Search** (EPUB), **Read aloud** (EPUB),
  **Aa** (EPUB: font, size, line spacing, light/sepia/dark — remembered for all books).
- Bottom: position slider and "Chapter title · 42%" (PDF: "Page 12 of 240").
- **Highlights** (EPUB): select text › **Highlight** saves a quote with the selected text, its chapter title
  and its position; it is drawn in the book. Tapping a highlight opens its quote (change or delete; deleting
  removes the highlight). Quotes added by hand have no position and are not drawn.
- **Read aloud** (EPUB): Android text-to-speech, play/pause, speed, the sentence being read is marked, and the
  reader follows it.
- **Search** (EPUB): results list with the words around each match; tapping one goes there.

### Progress, by itself

- The reader's position (`totalProgression`) becomes the book's progress percentage (rounded), saved at most
  every few seconds and when leaving the reader.
- Opening a To read or To buy book in the reader moves it to Reading (start date set as today).
- Reaching the end (≥ 99%) asks "Finished? Mark as Read" once per reading, like the quick progress update.
- The last position is restored when the book is opened again.

## How it is built

### Modules

- **`androidReader`** (new Android library module, used only by `androidApp`): Readium (`readium-shared`,
  `readium-streamer`, `readium-navigator`, `readium-adapter-pdfium`, `readium-navigator-media-tts`),
  `ReaderActivity` (a `FragmentActivity` hosting `EpubNavigatorFragment` or `PdfNavigatorFragment`, with the bars
  and sheets drawn in Compose over it), file inspection (DRM detection, metadata) and the Android
  `EbookPlatform` implementation. Core library desugaring is turned on where Readium needs it.
- **`shared`** (common code, also on iPhone): data, repository, Gutendex client, matching, backup, the book page
  card and Free classics screen.
- **`androidApp`**: intent filters for "Open with MyBooks", wiring.

### Data (database v7)

- `ebooks`: `bookId` (primary key, one file per book), `format` (EPUB/PDF), `fileName` (original name),
  `size`, `dateAdded`, `position` (Readium locator JSON, nullable), `lastOpened` (nullable).
- `bookmarks`: `id`, `bookId`, `position` (locator JSON), `label` (chapter title or page), `dateAdded`.
- `quotes` gains `position` (locator JSON, nullable): set for highlights.
- Files live in `files/ebooks/<bookId>.<epub|pdf>`; the backup rules only include `files/covers`, so ebooks stay
  out of Android's backup (a test asserts it).
- Deleting a book deletes its file, `ebooks` row, bookmarks and quotes.
- Backup JSON (version stays 1, fields optional): per book `ebook` (format, fileName, size, position, dateAdded,
  without the file), `bookmarks`, and quotes' `position`.

### Shared interfaces

- `EbookRepository(dao, bookmarksDao, files, books, clock)`: `add(bookId, format, bytes|path, fileName)`,
  `remove(bookId)`, `observe(bookId)`, `savePosition(bookId, locatorJson, progression)` (also updates progress,
  status and the finish prompt through `BookRepository`), bookmarks CRUD, `fileFor(bookId)`.
- `EbookMatcher`: `match(metadata: EbookMetadata, library) -> Match.Existing(book) | Match.New(book)`; metadata
  is title, authors, ISBN, language, cover bytes (optional).
- `ClassicsClient` (Ktor): `search(query) -> List<Classic>` and `download(classic) -> bytes`.
- Platform: `EbookPlatform?` in `PlatformServices` (null on iPhone): `pickFile()`, `inspect(file) -> Inspection`
  (`Readable(format, metadata)` | `AdobeProtected(metadata?)` | `Protected` | `Unreadable`), `openReader(bookId)`.

### Errors

- A file that isn't EPUB or PDF, is damaged, or is empty: "This file can't be opened as a book".
- A missing file (removed, or restored from backup): the "not on this phone" state, never a crash.
- Gutenberg/Gutendex unreachable or slow (timeout 30 s): message with Retry; a download that fails leaves no
  book behind.
- Readium failing to open a publication: back to the book page with "Couldn't open this book" and the reason.

## Testing

- Common tests: matching, positions to progress, To read → Reading, finish prompt, bookmarks, highlight quotes,
  removing files, backup round trip, v6 → v7 migration, Gutendex client against a fake server (results,
  slow/offline, not found).
- Android tests (Robolectric): DRM and format detection with small sample files (plain EPUB, EPUB with
  `rights.xml`, EPUB with Adobe `encryption.xml`, `.acsm`, plain PDF, encrypted PDF), intent filters, backup
  rules exclude `files/ebooks`.
- Screenshots: book page with the Ebook card, Free classics, the protected-file sheet.
- The reader itself (Readium draws in a WebView) is checked by hand on the phone before each PR with a Gutenberg
  EPUB and a small PDF: turning pages, position after reopening, progress, Aa, contents, bookmarks, highlights,
  search, read aloud.

## Delivery

Three PRs, each usable on its own and checked by the user before merging:

1. **Ebooks in the library**: database v7, files, matching, Add an ebook, Open with MyBooks, Free classics,
   protected files, the Ebook card, and a basic reader (EPUB and PDF, page turning, position and progress).
2. **A comfortable reader**: Aa (look and feel), contents, bookmarks, position slider.
3. **Reading tools**: highlights as quotes, search, read aloud.

## Risks and limits

- The app grows by about 10–15 MB (Readium, PDFium native libraries).
- Readium's PDFium adapter depends on PdfiumAndroid, which is no longer maintained; PDF stays behind
  `EbookPlatform`/`ReaderActivity` so it can be swapped (e.g. for Android's own PDF viewer) later.
- Readium's Compose renditions are experimental; the reader uses its Fragments.
- Gutendex is a free third-party service and can be slow or down; Free classics is the only part that needs it.
- No iPhone reader in this project (Readium Swift would be a separate project).
