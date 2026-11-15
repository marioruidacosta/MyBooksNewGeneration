# Reading widget: a stack of covers: design

**Date:** 2026-11-15
**Status:** approved in conversation (short design)

## Intent
The home-screen widget shows the actual covers of the books being read and lets the person flick through all of them
on the widget, instead of a text-only card for the most recently started book with "and N more".

## Today
`androidApp/.../widget/ReadingWidget.kt` (an `AppWidgetProvider` with plain `RemoteViews`, `widget_reading.xml`):
label, title, author, progress of `ReadingSummary.current`; "Reading · and N more"; this year's challenge. Tapping the
book opens its page, the challenge opens reading activity. Refreshed from `AppContainer.readingSummaries` (follows the
active library).

## Design (chosen: option A, a stack of covers)
- **Layout:** header "Reading · N books" ("Reading" for one), then a stack of cards, then the challenge row (unchanged).
  Default size 4×3 (was 4×2) so the covers fit; still resizable.
- **Cards:** one per book being read, most recently started first (the order `readingSummary` already uses). Each card:
  cover on the left; title (2 lines), author (1 line) and progress bar with percent (hidden when unknown). Flick up/down
  to bring the next card forward. Tapping a card opens that book's page.
- **Nothing being read:** the current "Nothing on the go / Pick your next book from To read" card, opening To read.
- **Covers:**
  - A cover photo (`cover:` reference) is read from `CoverStore`.
  - A web cover is downloaded once, shrunk (long side ~300 px), and kept in the app's cache, so the widget works
    offline and doesn't re-download. Failures fall back to the generated cover; never a blank card.
  - No cover: a generated cover bitmap (the app's colour for the title, with title and author), like the app's covers.
- **Updates:** as now: whenever the library changes (including an account switch), the widget's list is refreshed.

## Approaches
1. **`StackView` collection widget fed by a `RemoteViewsService` (chosen).** Android's built-in flickable stack; works
   on every supported Android version (minSdk 26).
2. `RemoteCollectionItems` (no service): simpler, but Android 12+ only.
3. Jetpack Glance: has no stack/pager; would mean a list instead.
(Option B, a scrolling list, and option C, arrows, were the alternatives for how it feels; A was picked.)

## Non-goals
- Horizontal swiping (home-screen widgets don't support it).
- Editing progress from the widget.
- iOS widgets.

## Tests
- The cards: one per book being read, in order, with title/author/progress.
- The cover chosen per card: photo, cached web cover, or generated; a failed download gives the generated cover; a
  cached web cover is reused without a second download.
- The "nothing on the go" state.
- Screenshots of a card (light and dark).
