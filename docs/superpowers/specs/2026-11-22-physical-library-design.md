# Physical library: design

**Date:** 2026-11-22
**Status:** written for approval
**Applies to:** Android app, website, and the iPhone app (the installed website)

## Intent

MyBooks' existing shelves (Reading, To read, Read, To buy) are logical lists. This section adds the real thing: a model
of the bookcases in the person's home, and the position of each physical book on them. The person can see their
bookcases as they stand in the room, and a book's page says where to go to find it.

Example: **Living room › By the window › Shelf A2, 4th from the left**.

## Goals

- Create rooms, bookcases, columns and shelves that match the real furniture, including columns with different numbers
  of shelves.
- Give a physical book a shelf and, optionally, a place on that shelf.
- See each bookcase drawn from the front, with books as spines in left-to-right order.
- Fill a whole shelf quickly: by scanning on Android, and by picking from the library on all three.
- See where a book is from its page, and list the books that have no place yet.
- Keep all of this in sync across the person's devices and in backups.

## Non-goals

- No duplicate book entries or multiple-copy tracking. One entry has one position.
- No floor plans, room drawings or furniture dimensions.
- No photo recognition of shelves, and no paid services.
- No iPhone or website barcode scanner in this version.
- No community features. Nobody else sees a home layout.

## Anti-goals (a failure even if it works)

- Deleting or changing furniture deletes or alters a book.
- The home layout, or a book's location, reaches the community, a shared book, or any other person.
- Apps already installed stop syncing, or lose data, because of this feature.
- Keeping the exact order becomes a chore: order is always optional.

## Constraints

- Free Supabase plan, offline first, Room on Android, per-field newest-wins sync.
- Each signed-in account and the signed-out phone library are separate libraries. A layout belongs to one library.
- Older apps must keep working. Today's Android app **fails a pull if it receives an unknown sync table**
  (`SupabaseSync.table()`), so no new sync table may be added. It safely ignores unknown preference keys and unknown
  book fields.
- Existing URLs and existing backups keep working. The backup stays version 1, with optional new fields.

## Approaches

1. **Each bookcase as a synced preference, plus two fields on each book (chosen by the user).** Uses the existing
   `library_prefs` sync. Installed apps ignore it. Smallest build. If two devices edit the same bookcase at the same
   moment, the later edit wins.
2. **New synced tables for rooms, bookcases and shelves.** Two devices could edit different shelves of one bookcase at
   once, but today's Android app would stop syncing unless new versions used a separate sync route, and there is much
   more to build and test. Rejected: furniture changes rarely, so this buys little for the risk.

## Data

### On each book

| Field | Server column (`library_books`) | Meaning |
|---|---|---|
| `shelfId` | `shelf_id text`, at most 40 characters | The shelf it is on; null means no place |
| `shelfPosition` | `shelf_position double precision` | Its order on the shelf; null sorts last |

- A migration adds both columns. `_merge_row` and `sync_pull` pick up new columns by themselves.
- Android adds both to `Book`, `BookEntity` (Room migration 8 to 9), `SyncCodec` and the backup book.
- The website adds both to `Book`, `codec.ts`, `backup.ts` and the import plan.
- Both fields are always written together in one change, with the same time.
- They are **not** part of what is shared: `publicState` and `publicFieldsDiffer` stay as they are on both platforms.

### Each bookcase

One synced preference per bookcase, key `bookcase.<uuid>`, value JSON (the server allows 20,000 characters):

```json
{
  "v": 1,
  "name": "By the window",
  "room": "Living room",
  "columns": [
    { "id": "<uuid>", "shelves": ["<shelf uuid>", "<shelf uuid>"] },
    { "id": "<uuid>", "shelves": ["<shelf uuid>"] }
  ]
}
```

- **Rooms are not records.** A room is the text on its bookcases, and the room list is derived from them. Renaming a
  room updates its bookcases.
- **Limits:** names up to 60 characters, 1 to 10 columns, 1 to 15 shelves per column.
- **Labels are computed**, never stored: columns A, B, C from the left, shelves numbered from the top. Removing a shelf
  renumbers the labels, but books stay on their shelf because they point to its id.
- Rooms sort by name, and bookcases by name within a room.
- A value that can't be read, or has a newer `v`, is left untouched and not shown as editable.

## Rules

**What can have a place**
- **To buy:** none. It isn't offered in pickers. If a placed book becomes To buy, its fields are kept but it is hidden
  from shelves, and it returns if the status changes back.
- **Ebooks:** a book with an ebook is left out of **Without a place** and pickers. Its own page can still place it,
  because one entry can be both an ebook and a paper copy.
- **Lent:** keeps its place, and its spine shows as away. Its page shows both the loan and the location.

**Without a place** lists books that are not To buy, have no ebook, and whose `shelfId` is null or matches no existing
shelf.

**Order**
- A newly placed book goes to the end of the shelf (highest position + 1).
- Moving a book inside a shelf gives it the midpoint between its neighbours, so only that book changes. When the gap is
  too small to split, that shelf is renumbered.
- On a shelf, books sort by position, then title.

**Changing furniture**
- Removing a shelf, column or bookcase asks for confirmation, saying how many books are affected. Those books go to
  **Without a place**, and their fields are cleared. Book records are otherwise untouched.
- If a sync removes a shelf another device still references, the book simply reads as having no place.

**Merging duplicates (Tidy up, first sync):** the two fields move as a pair, from the book that has a valid place. If
both do, from the book being kept.

**Library changes:** a layout must follow its books on every path.
- First merge pushes the keys listed by `LibraryPrefs`, so `bookcase.*` must be listed there.
- `Libraries.moveToPhoneAndRemove` moves books through `transfer.move`, so it must also carry the account's `bookcase.*`
  preferences into the phone library. Otherwise the moved books would lose their place.

**Backups:** the file gains an optional top-level `bookcases` array of `{ "id": "<uuid>", "value": "<the JSON above>" }`,
and each book gains optional `shelfId` and `shelfPosition`. Restoring replaces a bookcase with the same id, as it
replaces the same year's reading challenge, and adds the others. Older backups restore with no layout.

## What the person sees

Approved mockups: rooms with small bookcases, a create form, the front view, and the book's page. Books are drawn as
**spines**, coloured by the existing generated cover colour for the title.

1. **Physical library** (under **More** on Android and iPhone, in the top menu on desktop). Rooms, each listing its
   bookcases with a small drawing, shelf count and book count. A **Without a place** row and **Add a bookcase**.
2. **New / edit bookcase.** Name, room (choose or type), columns and shelves steppers, and a live drawing. When editing,
   each column can gain or lose a shelf.
3. **Bookcase.** The front view with labelled shelves. Spines are too small to tap on a phone, so **tapping a shelf opens
   it**. A shelf with more spines than fit shows "+N".
4. **Shelf.** Its books in order as full rows with title and author. Open a book, remove it from the shelf, change the
   order by dragging (with an accessible alternative), and add books: **Scan** (Android) or **Pick from library** (all).
5. **Book page.** A **Where it is** card with the full location, a small drawing highlighting the shelf and the book,
   and **Put on a shelf** / **Move**. It is hidden for To buy.
6. **Without a place.** The list, with a way to choose several books and put them on a shelf.

**Pick from library:** search, tick books, confirm. A book already on another shelf shows where it is, and picking it
moves it. The result offers Undo.

**Scan onto a shelf (Android, reusing the batch scanner):** scanning left to right builds the order.
- In the library, no place or another shelf: placed at the end, with Undo.
- Already on this shelf: left where it is, and the scan says so.
- Not in the library: looked up, added as To read, and placed.
- Not found: offered to add by hand, then placed.

## Release

1. Apply the additive database migration.
2. Publish the website and install the Android version. Order between them doesn't matter once the columns exist.
3. Older apps keep syncing and ignore the new data. An older app does not clear these fields, because it pushes only the
   fields it knows.

## Testing focus

- Older client behaviour: unknown pref key and unknown book fields, on both platforms.
- Every path that rebuilds a book keeps the two fields: edit form, status change, loan, merge, import, restore, sync.
- Nothing about location appears in the public book data.
- Removing furniture never deletes a book, and orphaned shelf ids read as no place.
- Concurrent edits, offline moves, and order after renumbering.
- Bookcases with uneven columns on phone and desktop, including long names and full shelves.

## Left to the builder

Class and file names, the exact drag gesture and its accessible alternative, spine sizing, the "+N" threshold, and the
order of implementation.
