# Animations: design

**Date:** 2026-11-15
**Status:** approved in conversation (short design; all seven suggestions)

## Intent
Make the app feel alive with short, subtle motion, without making anything slower.

## Design
1. **Cover into the book page.** Tapping a book in Home, a shelf (library list/grid), search, New in Portugal and the
   author panel's "other books": its cover moves and grows into the book page's header cover (shared element), and back
   on Back. Screens cross-fade around it.
2. **Progress that moves.** Progress bars animate to their new value (book page reading card, Home's Reading row, the
   challenge on Home and Reading activity). Year in review's numbers count up from 0 when the screen opens.
3. **Celebration.** Finishing a book / completing the challenge: the existing celebration dialog gets a ~1.5 s confetti
   burst (drawn on a Canvas, no new library) and the cover bounces in.
4. **Status selector** (Reading / To read / Read / To buy): the selected highlight slides to the new choice.
5. **Rating stars:** tapping pops the stars up to the tapped one.
6. **Lists:** items animate in when added and close the gap on delete (library lists, search results).
7. **Cover loading:** a soft shimmer in the cover's place until the image arrives; a failed load still shows the
   generated cover.

## Rules
- About 250 ms per transition (confetti ~1.5 s). Nothing waits on an animation; taps act immediately.
- **Reduced motion:** when the phone's animations are off (Android's animator duration scale is 0 —
  Settings → Accessibility → Remove animations), everything snaps to its end state and there is no confetti. Exposed to
  shared UI as `LocalReduceMotion` (iOS: `UIAccessibility.isReduceMotionEnabled`).
- No new dependencies: Compose Multiplatform 1.12 (`SharedTransitionLayout`, `animate*AsState`, `animateItem`) and
  Navigation Compose 2.10.

## Approaches
Built-in Compose animation APIs (chosen) — the only sensible route: the app is Compose Multiplatform, the needed APIs
are already in the versions it uses, and a third-party animation library (Lottie, confetti libs) would add size and an
iOS gap for a ~1.5 s effect.

## Non-goals
- Page-turn effects in the ebook reader (Readium owns the reader).
- Animating the home-screen widget.
- Custom animation settings in the app (the phone's setting rules).

## Tests
- Each animated element reaches the right final state (bar at the new value, count at the final number, the
  confetti finishes and goes away, the highlight on the chosen status, stars filled to the tapped one).
- With reduced motion, the final state is immediate and no confetti is drawn.
- The shared-element book page still opens and shows the book (and Back returns) from each listed entry point.
- Screenshots: celebration with confetti mid-burst, and a shimmering cover.
