# Animations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the seven animations from the spec, all of which respect the phone's "Remove animations" setting.

**Architecture:**
- A small motion toolkit in `ui/components/Motion.kt`: `LocalReduceMotion`, `animatedProgress`, `CountUp`, `Confetti` and the shimmer.
- Each screen wires in its piece.
- The cover moving into the book page uses `SharedTransitionLayout` around the NavHost. Screens opt in through a `shareKey` on `BookCover`.

**Tech Stack:** Compose Multiplatform 1.12 (animation, `SharedTransitionLayout`, `animateItem`), Navigation Compose 2.10, Robolectric Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-11-15-animations-design.md`

## Global Constraints
- **Timing:** transitions are 250 ms, count-up is 800 ms, confetti is about 1500 ms. Nothing waits on an animation, and taps act immediately.
- **Reduced motion:** `LocalReduceMotion` (default `false`) is provided in `App.kt` from `rememberReduceMotion()`.
  - Android reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`. iOS reads `UIAccessibilityIsReduceMotionEnabled()`.
  - When it is true, every animation shows its end state immediately and confetti is not drawn.
- **No new dependencies.**
- **Copy and behaviour are unchanged.** Existing tests that find nodes by text must keep passing.
- **Gate** (wrap every run in `timeout`):
  1. `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && timeout 1800 ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`
  2. `timeout 1800 ./gradlew :androidReader:lintDebug :androidApp:lintDebug :shared:compileKotlinIosSimulatorArm64 -q`
- **Robolectric tests** use `@Config(sdk = [35])`.
- **Kotlin/Native:** never write a suspend extension whose receiver is an indexed Room `@Entity`.
- **Line endings:** keep each file's existing line endings.

## Review Focus
1. **Phone animations turned off.** Every element shows its final state at once and no confetti appears. Pinned by Task 1 `reducedMotionShowsTheEndStateAtOnce` and Task 4 `noConfettiWithReducedMotion`.
2. **Rapid taps during a transition** (Back while the cover is still moving, or tapping another book). Navigation still works and lands on the right screen. Pinned by Task 5 `openingABookAndGoingBackStillWorks`.
3. **A cover that fails to load.** The shimmer ends and the generated cover shows; the shimmer never stays forever. Pinned by Task 2 `aFailedCoverEndsTheShimmer`.
4. **The same book appearing twice on one screen** (Home's Reading row and a shelf row). Shared-element keys must be unique per screen, otherwise the transition is wrong or crashes. Keys come from `shareKey`, and only one place per screen passes a given book's key. Pinned by Task 5 `homeUsesOneShareKeyPerBook`.
5. **Progress changed several times quickly.** The bar ends at the last value. Pinned by Task 1 `progressEndsAtTheLatestValue`.

---

### Task 1: Motion toolkit
**Files:**
- Create: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/components/Motion.kt`
- Create: `shared/src/androidMain/kotlin/com/mcosta/mybooks/ui/components/ReduceMotion.android.kt` and `shared/src/iosMain/kotlin/com/mcosta/mybooks/ui/components/ReduceMotion.ios.kt`
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/App.kt` (provide the local)
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/MotionTest.kt`

**Produces:**
- `val LocalReduceMotion = staticCompositionLocalOf { false }`
- `@Composable expect fun rememberReduceMotion(): Boolean`
- `@Composable fun animatedProgress(target: Float): Float` uses `animateFloatAsState(target, tween(250))`, or returns `target` when reduced.
- `@Composable fun CountUp(value: Int, style: TextStyle, modifier: Modifier = Modifier)` is a `Text` that counts from 0 to `value` in 800 ms on first composition. When reduced it shows `value` at once.
- `fun Modifier.shimmer(): Modifier` paints an animated diagonal highlight over `surfaceVariant`. When reduced it is a plain `surfaceVariant` background.

- [ ] **Step 1: Failing tests.** Use a Compose rule with `mainClock.autoAdvance = false`.
  - `progressEndsAtTheLatestValue`: the target changes 0.2, then 0.6, then 0.9 within 100 ms. After 300 ms the shown value is 0.9.
  - `countUpEndsAtTheValue`: after 900 ms the text reads "37".
  - `reducedMotionShowsTheEndStateAtOnce`: under `LocalReduceMotion provides true`, at time 0 the progress is the target and the count-up reads "37".
- [ ] **Step 2: Run** `timeout 1200 ./gradlew :androidApp:testDebugUnitTest --tests '*MotionTest*' -q`. Expected: FAIL (undefined).
- [ ] **Step 3: Implement** the toolkit and provide `LocalReduceMotion` in `App.kt`.
- [ ] **Step 4: Run** again. Expected: PASS.
- [ ] **Step 5: Commit** "Motion: reduced-motion aware progress, count-up and shimmer".

### Task 2: Progress, numbers and loading covers
**Files:**
- Modify: `ui/detail/ReadingSection.kt:71`, `ui/home/HomeScreen.kt:285,339`, `ui/activity/ReadingActivityScreen.kt:187`, `ui/activity/YearReviewScreen.kt` (`BigNumber` gets a count-up for numeric values), `ui/celebrate/CelebrationDialog.kt:53`, `ui/components/BookCover.kt` (loading shows a shimmer box; error still shows the placeholder).
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/MotionTest.kt`
- [ ] **Step 1: Failing test** `aFailedCoverEndsTheShimmer`. `BookCover("https://unreachable.invalid/x.jpg", title = "Stoner")` ends, after idling, with the generated cover's title "Stoner" visible and no node tagged `cover-shimmer`. During loading, `cover-shimmer` exists.
- [ ] **Steps 2–4:** red, implement, green. The progress bars use `animatedProgress(...)` inside their `progress = { }`. `BigNumber(value: String…)` becomes `CountUp` when `value.toIntOrNull() != null`.
- [ ] **Step 5: Commit** "Motion: moving progress bars, counting numbers, shimmering covers".

### Task 3: Status selector and stars
**Files:**
- Modify: `ui/components/StatusSelector.kt`, `ui/components/RatingStars.kt`
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/MotionTest.kt`
- **Status selector:** keep `SingleChoiceSegmentedButtonRow` and its labels. Animate the selected segment's container colour with `animateColorAsState(tween(250))` through `SegmentedButtonDefaults.colors`. The checkmark icon fades in. The visible effect is that the highlight glides to the new choice.
- **Stars:** when `rating` changes, the stars `1..rating` pop: scale 1 → 1.25 → 1, staggered by 40 ms. This is skipped when reduced.
- [ ] **Step 1: Failing tests.**
  - `choosingAStatusMovesTheHighlight`: click "Read", and `onChange(READ)` is called. After 300 ms the "Read" segment is selected.
  - `tappingAStarRatesAndPops`: click "Rate 3", and `onRate(3)` is called. A star's `graphicsLayer` scale is above 1 at 80 ms and back to 1 at 400 ms.
- [ ] **Steps 2–4:** red, implement, green.
- [ ] **Step 5: Commit** "Motion: gliding status highlight, popping stars".

### Task 4: Celebration confetti and list items
**Files:**
- Modify: `ui/celebrate/CelebrationDialog.kt` (a `Confetti()` layer over the dialog's content, behind the buttons), `ui/library/LibraryScreen.kt:256,260` (`Modifier.animateItem()` on the items, keyed by id), and the outside-search result list.
- Add `@Composable fun Confetti(modifier: Modifier = Modifier)` to `Motion.kt`, tagged `confetti`.
  - It draws about 60 particles in the theme colours on a `Canvas`, driven by one `Animatable(0f → 1f, 1500 ms)`, with gravity and spin.
  - It removes itself when done, and is not composed at all when reduced.
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/MotionTest.kt`
- [ ] **Step 1: Failing tests.**
  - `confettiBurstsThenGoes`: `confetti` exists at 200 ms and is gone at 1700 ms.
  - `noConfettiWithReducedMotion`: no `confetti` node at 0 ms or 200 ms.
  - Screenshot test `celebrationConfettiLight`: the celebration dialog at 400 ms.
- [ ] **Steps 2–4:** red, implement, green. Look at the screenshot.
- [ ] **Step 5: Commit** "Motion: confetti when a book is finished, animated list items".

### Task 5: The cover grows into the book page
**Files:**
- Modify: `ui/MyBooksNavHost.kt`. Wrap the `NavHost` in `SharedTransitionLayout`. Provide `LocalSharedTransitionScope`, and provide `LocalNavAnimatedScope` inside each `composable` that shows covers: library, home, search, releases, release book, catalog book, and the book page.
- Modify: `ui/components/BookCover.kt`. Add `shareKey: String? = null`. When the key and both locals are present, and motion is not reduced, apply `sharedElement(rememberSharedContentState(shareKey), animatedVisibilityScope = scope, boundsTransform = { _, _ -> tween(250) })`.
- Modify: the callers. Library grid/list cells, Home's Reading row and shelves, search results, the author-panel's other books, and `BookHeader` all pass `shareKey = "book-$id"` for library books. Release and catalog books pass `"isbn-$isbn"` when there is an ISBN.
- Home shows a book in both the Reading row and a shelf row. Only the Reading row passes the key; the other gets `null`.
- Test: `androidApp/src/test/kotlin/com/mcosta/mybooks/CoverTransitionUiTest.kt`
- [ ] **Step 1: Failing tests.**
  - `openingABookAndGoingBackStillWorks`: from the library list, tap a book. Its page shows the title. Press back during the transition at 100 ms, and the library list shows again.
  - `homeUsesOneShareKeyPerBook`: compose Home with a book that is in both the Reading row and a shelf. Collect the semantics property `ShareKey` (a test-only `SemanticsPropertyKey` set by `BookCover` when `shareKey != null`), and assert no duplicates.
- [ ] **Steps 2–4:** red, implement, run the full gate (both commands), green.
- [ ] **Step 5: Commit** "Motion: the cover grows into the book page".
- [ ] **Step 6: On the phone** (after a DB backup), install and check all seven animations by hand. Then turn on Remove animations and check that everything snaps.
