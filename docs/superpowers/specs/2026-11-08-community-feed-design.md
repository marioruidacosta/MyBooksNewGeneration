# Community feed, evolved: richer cards, grouped updates, what friends are reading

**Builds on:** Social part 2 (`2026-11-02-social-part2-activity-feed-design.md`) and book reviews.
**Branch:** `feature/community-feed` (from `main`).

## Why

The Community tab works, but its cards are small: a line of action, a little cover, a heart. The user finds
Goodreads' community feed much nicer and wants ours to evolve in that direction, taking its layout ideas and
features (not its name, look or artwork):

- **Richer cards**: the update first, then the book in its own panel with a bigger cover, blurb, page progress
  and a one-tap "Want to read".
- **Grouped updates**: several books added at once become one card.
- **Who's reading what**: a row of what friends are reading now, and, on a book's page, which friends have it.

Comments are not wanted. Mock-ups chosen with the user: card **B** ("book in its own panel") and the
"Friends are reading" row **A** (covers with the friend's face).

## What the user gets

### The card

1. **The update**, as today's line ("Ana started reading"), with these changes:
   - progress: "is on page 182 of 344" when the page and page count are known; otherwise "is 53% through";
   - ratings show coloured stars (★★★★☆) on their own line, under the name, for rated and reviewed entries.
2. **The review**, when there is one, up to 4 lines in quotes; tapping expands it.
3. **The book panel** (rounded, slightly raised background):
   - cover (64×96 dp), title, "author · 344 pages" (pages when known);
   - a progress bar and percentage on progress entries;
   - the blurb, up to 3 lines, when the book has one;
   - one full-width button:
     - **Want to read** (filled) when the book isn't in the user's library: one tap adds it as To read, after
       which the button is the "in your library" one below; the panel's ⋮ menu also offers **To buy**;
     - **✓ In your library · {shelf}** (outlined) when it is (same ISBN, or same title and first author, like
       the catalogue): tapping opens the user's copy.
   - Tapping the panel (outside the button) opens the book sheet as today.
4. **Likes and Report** as today. The user's own entries show no button (it's their book).

### Grouped updates

- Consecutive entries by the **same person**, of the **same kind**, where the kind is **wants to read** or
  **wants to buy**, each within **24 hours** of the previous one, become one card:
  "Marta added 5 books to To read" (or To buy), with up to 4 covers and a "+N" tile for the rest.
- Tapping a cover opens that book's sheet; tapping "+N" expands the card to show all covers.
- Grouped cards have no heart (likes stay on single updates). A group of one is a normal card.
- Grouping is redone as more pages load, so a group split by a page boundary joins up.

### Friends are reading

- On the **Friends** tab only, above the updates: "Friends are reading", a horizontal row of covers (72×108 dp)
  of books friends have on Reading, most recently updated first, up to 20.
- Each cover carries the friend's avatar on its corner, and below it a thin progress bar and "Ana · 53%".
- Tapping a cover opens the book sheet (add to my library). **See all** opens a list: friend, book, progress.
- Hidden when no friend is reading anything, when signed out, and if it fails to load.

### Book page: friends who have this book

- On a book's page, when signed in and the book has an ISBN: "Friends who have this book", one row per friend
  who shares a book with that ISBN: avatar, name, and "read it · ★★★★★" (with a review excerpt in quotes),
  "is reading it · 40%", "wants to read it" or "wants to buy it". Tapping a row opens the friend's profile.
- Hidden when no friend has it, and if it fails to load.

### Privacy

Nothing beyond what is already shared: a friend's book appears only if that friend shares activity, hasn't
marked the book "Don't share", and neither has blocked the other. The Everyone tab has no "reading now" row,
and the book page lists friends only.

## How it fits

### Server (`supabase/migrations/2026110800_community_feed.sql`)

- `shared_books` gains `blurb text check (char_length(blurb) <= 600)`, `pages int check (pages between 1 and
  20000)`, `current_page int check (current_page >= 0)`; all nullable.
- `publish_book` stores them (they never create activity on their own); `feed` and `person_activity` return the
  book's `blurb`, `pages` and `current_page` with each entry (as `book_blurb`, `book_pages`, `book_current_page`).
- `friends_reading()` → rows (person, book: share id, isbn, title, authors, cover url, pages, current page,
  progress, updated at) for books with status reading owned by the caller's friends who share activity and
  aren't blocked either way; newest `updated_at` first; at most 20. `require_user()` as the other functions.
- `friends_with_book(p_isbn text)` → rows (person, status, rating, progress, review excerpt (first 200
  characters)) for the caller's friends (same rules) sharing a book with that ISBN; ordered read, reading, to
  read, to buy, then name.
- pgTAP tests in `supabase/tests/community_feed_test.sql`: columns stored and returned; no activity from blurb or
  pages alone; both functions return friends only, skip non-sharing friends, blocked users (either way) and
  strangers; the limit and order.

### Phone

- `PublicBookState` gains `blurb`, `pages`, `currentPage`; `Book.publicState` fills them (blurb = the book's
  description, whitespace collapsed, first 600 characters). `FeedBook` gains `blurb`, `pages`, `currentPage`.
- `ActivityService` gains `friendsReading(): List<FriendReading>` and `friendsWithBook(isbn): List<FriendWithBook>`.
- **`groupFeed(entries: List<FeedEntry>): List<FeedItem>`** (pure): `FeedItem.Single(entry)` or
  `FeedItem.Group(actor, kind, entries)` by the rules above.
- `ActivityViewModel` exposes items (grouped), the friends-reading row (Friends tab), and the user's library
  index to decide each card's button; "Want to read"/"To buy" add the book through the existing add path.
- `FeedEntryCard` is rebuilt as card B; new `FeedGroupCard`, `FriendsReadingRow`, `FriendsReadingScreen`
  (See all), and `FriendsWithBookSection` on the book page (via `BookDetailViewModel`, social optional).

## Out of scope

Comments; grouping other kinds (ratings, finishes); a "reading now" row on the Everyone tab; friends who have
a book without an ISBN; showing the blurb of books whose owners have no description.

## Testing

- pgTAP as above (CI `supabase.yml`).
- Kotlin: `groupFeed` (same person and kind within 24 h; other kinds never; a break by another person; page
  boundary rejoin; group of one); card text (page vs percentage, stars line); the button's state from the
  library (by ISBN, by title and first author); "Want to read" adds To read and the card updates; the friends
  row hidden when empty or failing; `friendsWithBook` rows' text; `publicState` blurb trimming.
- Screenshots: Community with the row and both card kinds (light and dark); a book page with the friends section.
