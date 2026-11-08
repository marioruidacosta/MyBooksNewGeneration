# Search everywhere: library first, then New in Portugal and public sources

## Why

Search finds only books already in the library, and only by title or author. When a book isn't there, the user
wants to find it without leaving Search: among the monthly new books (New in Portugal) and in public catalogues,
see it, and add it to To read or To buy. An ISBN typed into Search should work everywhere too.

## What the user gets

On the **Search** tab (not on a shelf's search, which filters that shelf):

1. **Library first**, as today: books whose title or author matches.
2. When the library has **no match**, the query has at least 3 characters (or is an ISBN), and typing has paused for
   0.8 s, two sections appear below "No books match", searched automatically and in parallel:
   - **New in Portugal**: books from every listed month whose title or author matches; up to 20, newest month first.
     A row shows cover, title, authors and month. Tapping opens the existing New in Portugal book page (To read,
     To buy, where to buy).
   - **Public sources**: Google Books and Open Library searched by text; up to 20, Portuguese editions first.
     A row shows cover, title, authors and year. Tapping opens the existing catalogue-book page (To read, To buy,
     where to buy).
3. Typing again cancels searches in flight; a library match hides both sections.

### ISBN

A query that is a valid ISBN-10 or ISBN-13 (hyphens and spaces allowed, normalized to ISBN-13) is an ISBN search:

- **Library**: the book with that ISBN (exact match), instead of the title/author filter.
- **New in Portugal**: the book with that ISBN.
- **Public sources**: the existing ISBN lookup chain (Biblioteca Nacional, Google Books, Open Library), giving at
  most one result, which opens the catalogue-book page.
- The 3-character minimum doesn't apply.

A 10- or 13-digit query whose check digit is wrong is searched as text, with the hint
"Not a valid ISBN — check the digits".

### Empty results and errors

- Each section loads, fails and retries on its own: "Searching…", then results, "Nothing in New in Portugal" /
  "Nothing found online", or "Couldn't search New in Portugal" / "Couldn't search online" with **Retry**.
- One public source failing doesn't hide the other's results; the section fails only when both fail.
- New in Portugal uses the saved months when offline (as its own search does); months that couldn't be loaded are
  searched without them.
- Below the sections: "Not here? **Add it yourself**" opens the add-book form (with the ISBN when the query is one).

## How it fits

- **`PublicBookSearch`** (shared, data): `suspend fun search(query: String): List<AuthorBook>` over Google Books
  (`volumes?q=…&printType=books`, API key if set) and Open Library (`search.json?q=…`), in parallel; merged and
  de-duplicated by ISBN-13, else by `catalogTitleKey(title)` + first author's `searchKey`; Portuguese-language
  editions first; at most 20. Throws only when both sources fail. Results go to the existing `CatalogBooks` store so
  the catalogue-book page can open them.
- **ISBN in public search**: the existing `LookupChain` gives a `Book`, mapped to an `AuthorBook`.
- **New in Portugal match** (shared, data): a pure function over `AllMonths` — title or any author contains the
  query (`searchKey`), or the ISBN equals it — returning `(month, ReleaseBook)` pairs, newest month first, at most 20.
- **`OutsideSearchViewModel`** (shared, ui): takes the query and whether the library matched; debounces 0.8 s;
  cancels the previous search; holds one state per section (`Idle`, `Searching`, `Results`, `Empty`, `Failed`);
  `retry(section)`. Months are loaded once per screen and reused for later queries.
- **Library ISBN match**: `LibraryViewModel` uses `findByIsbn` when the query is an ISBN (no database change).
- **Search screen**: `LibraryScreen` in Search mode shows the hint and the two sections under the empty library
  list. Shelves are unchanged.

## Out of scope

- Searching the Biblioteca Nacional by text (its service answers only by ISBN).
- Adding outside results straight from the list (their pages already do it).
- Searching outside sources while the library has matches.

## Testing

- `OutsideSearchViewModel`: waits for the pause; cancels a superseded search; runs only when the library has no match
  and the query is long enough or an ISBN; each section fails and retries independently.
- `PublicBookSearch`: Google Books and Open Library responses parsed (MockEngine); merge and de-duplication;
  Portuguese first; one source failing keeps the other's results; both failing throws.
- New in Portugal match: title, author, accents and case; ISBN; newest month first; limit 20.
- ISBN: ISBN-10 and hyphenated forms normalized; a wrong check digit is text plus the hint; library finds by ISBN.
- Screenshot of Search with both sections.
