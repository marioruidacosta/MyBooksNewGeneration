# Cover from an Existing Photo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the person choose a photo already on the phone as a book's cover, alongside taking one with the camera.

**Architecture:**
- A new platform service, `CoverPhotoPicker`, sits beside `CoverCamera`. It is implemented on Android with the system photo picker and is null on iOS.
- The camera and the picker share one path: shrink, store in `CoverStore`, set the `cover:` reference. That path moves into a plain suspend function so it can be unit tested.
- The UI offers the choice only where a picker exists.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AndroidX Activity Result (`PickVisualMedia`), okio, Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-15-cover-from-photos-design.md`

## Global Constraints

- Copy, verbatim:
  - book-page menu items "Take photo" and "Choose from photos";
  - edit-form button "Choose photo";
  - error prefix "Couldn't use the photo: ".
- Android uses `ActivityResultContracts.PickVisualMedia` with `PickVisualMedia.ImageOnly`. No new permissions in the manifest.
- iOS: `photoPicker` stays null, and the choose options are hidden there. The book page's "Add a cover photo" opens the camera directly, as today.
- A picked photo goes through the same shrink-and-store path as a camera photo: `PhotoShrinker.shrink`, then `CoverStore.save`.
- Gate (wrap every run in `timeout`):
  - `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT && timeout 1800 ./gradlew :releases:test :shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidReader:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -q`
  - then `timeout 1800 ./gradlew :androidReader:lintDebug :androidApp:lintDebug :shared:compileKotlinIosSimulatorArm64 -q`.
- Never declare a `suspend` extension function whose receiver is a Room `@Entity` with `indices`, because it crashes the Kotlin/Native compiler.
- Keep each file's existing line endings.

## Review Focus

1. **A photo the phone can't open.** Examples: a cloud-only Google Photos item, one deleted meanwhile, or an unsupported format. Expected: the "Couldn't use the photo: …" message, the cover unchanged, no crash. Pinned by Task 1 `unreadablePhotoGivesTheMessage` and Task 2 `aPhotoThatCantBeOpenedFails`.
2. **A very large photo** (a 12+ MP gallery image). Expected: stored shrunk, like a camera photo. Pinned by Task 1 `pickedPhotoIsShrunkStoredAndReferenced`, which asserts the shrinker ran before storing.
3. **Cancelling the picker.** Expected: nothing changes and no message appears. Pinned by Task 1 `cancelledPickChangesNothing`.
4. **iOS, or anywhere without a picker.** Expected: no choose option, and "Add a cover photo" opens the camera directly. Pinned by Task 3 `withoutAPickerTheCameraOpensDirectly`.

---

### Task 1: Shared cover-photo path and the picker service

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/platform/PlatformServices.kt`
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/components/CoverPhoto.kt`
- Test: `shared/src/commonTest/kotlin/com/mcosta/mybooks/ui/components/CoverPhotoTest.kt`

**Interfaces:**
- **Produces in `PlatformServices.kt`:**
  - `interface CoverPhotoPicker { suspend fun pickPhoto(): ByteArray? }`. It returns null if the user cancelled, and throws with a message if the photo can't be read.
  - On `PlatformServices`, a new last parameter `val photoPicker: CoverPhotoPicker? = null`, with KDoc: "Null where choosing an existing photo isn't available (iPhone for now): the option is then hidden."
- **Produces in `CoverPhoto.kt`:**
  - `internal sealed interface CoverPhotoResult`, with three cases: `data class Stored(val ref: String)`, `data object Cancelled`, and `data class Failed(val message: String)`.
  - `internal suspend fun storeCoverPhoto(photo: suspend () -> ByteArray?, shrinker: PhotoShrinker, store: CoverStore): CoverPhotoResult`. Shrinking and storing run on `Dispatchers.Default`. `CancellationException` is rethrown. Any other exception becomes `Failed("Couldn't use the photo: ${e.message ?: "unknown error"}")`.
  - `rememberCoverPhotoTaker(onPhoto, onError)` keeps its signature and now uses `storeCoverPhoto { services.camera.takePhoto() }`.
  - New: `@Composable fun rememberCoverPhotoChooser(onPhoto: (String) -> Unit, onError: (String) -> Unit): (() -> Unit)?`. It returns null when `LocalPlatformServices.current.photoPicker` is null; otherwise it works like the taker, with `picker.pickPhoto()`.

- [ ] **Step 1: Write failing tests** in `CoverPhotoTest`. Use `CoverStore(FakeFileSystem(), "/covers".toPath())` and a recording fake `PhotoShrinker`.
  - `pickedPhotoIsShrunkStoredAndReferenced`: photo returns `byteArrayOf(1, 2, 3)` and the fake shrinker returns `byteArrayOf(9)`. Assert:
    - the result is `Stored`, and its `ref` starts with `CoverStore.PREFIX`;
    - the shrinker received `[1, 2, 3]`;
    - the stored file's bytes are `[9]`.
  - `cancelledPickChangesNothing`: photo returns null. Assert the result is `Cancelled`, the shrinker was never called, and the store's directory has no files.
  - `unreadablePhotoGivesTheMessage`: photo throws `IllegalStateException("bad image")`. Assert the result is `Failed("Couldn't use the photo: bad image")`.
- [ ] **Step 2: Run.** `timeout 900 ./gradlew :shared:testAndroidHostTest --tests '*CoverPhotoTest*' -q`. Expected: FAIL, because `storeCoverPhoto` is undefined.
- [ ] **Step 3: Implement** the interface, the parameter, `storeCoverPhoto`, the refactored taker and `rememberCoverPhotoChooser`.
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit:** "Cover photos: shared store path and a photo-picker service".

### Task 2: Android photo picker

**Files:**
- Modify: `shared/src/androidMain/kotlin/com/mcosta/mybooks/platform/AndroidCoverCamera.kt` (beside `AndroidCoverCamera`, plus `androidPlatformServices`)
- Test: `shared/src/androidHostTest/kotlin/com/mcosta/mybooks/platform/AndroidCoverPhotoPickerTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `CoverPhotoPicker` (Task 1).
- **Produces:**
  - `class AndroidCoverPhotoPicker(private val activity: ComponentActivity, private val results: ActivityResults) : CoverPhotoPicker`. It launches `PickVisualMedia()` with `PickVisualMediaRequest(PickVisualMedia.ImageOnly)` and returns null on a null Uri (cancelled). Otherwise it returns `readPickedPhoto(activity.contentResolver, uri)` on `Dispatchers.IO`.
  - `internal fun readPickedPhoto(resolver: ContentResolver, uri: Uri): ByteArray`. It throws `IllegalStateException("The photo couldn't be opened")` when the stream is null or opening it throws `FileNotFoundException` / `SecurityException`.
  - `androidPlatformServices` passes `photoPicker = AndroidCoverPhotoPicker(activity, results)`.

- [ ] **Step 1: Write failing tests.**
  - `aPickedPhotoIsRead`: register content with Robolectric's `ShadowContentResolver.registerInputStream(uri, ByteArrayInputStream(byteArrayOf(4, 5)))`. Assert `readPickedPhoto` returns `[4, 5]`.
  - `aPhotoThatCantBeOpenedFails`: use an unregistered `content://` Uri. Assert `assertFailsWith<IllegalStateException>` with the message "The photo couldn't be opened".
- [ ] **Step 2: Run.** `timeout 900 ./gradlew :shared:testAndroidHostTest --tests '*AndroidCoverPhotoPickerTest*' -q`. Expected: FAIL.
- [ ] **Step 3: Implement** the class and the function, and wire up the services.
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit:** "Cover photos: Android photo picker".

### Task 3: Offer it in the book page and the edit form

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/detail/BookHeader.kt` (the "Add a cover photo" button, line ~85)
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/detail/BookDetailScreen.kt` (line ~130 and ~167)
- Modify: `shared/src/commonMain/kotlin/com/mcosta/mybooks/ui/edit/EditBookScreen.kt` (line ~62 and ~108)
- Test:
  - `androidApp/src/test/kotlin/com/mcosta/mybooks/CoverFromPhotosUiTest.kt` (Robolectric)
  - `androidApp/src/test/kotlin/com/mcosta/mybooks/ScreenshotTest.kt`

**Interfaces:**
- Consumes: `rememberCoverPhotoChooser` and `photoPicker` from Task 1.
- **Produces:**
  - `BookHeader` gains `onChoosePhoto: (() -> Unit)? = null`.
  - When both `onAddPhoto` and `onChoosePhoto` are non-null, "Add a cover photo" opens a `DropdownMenu` with "Take photo" (which calls `onAddPhoto`) and "Choose from photos" (which calls `onChoosePhoto`).
  - With only `onAddPhoto`, the button calls it directly, as today.
  - The edit form shows `TextButton("Choose photo")` under the cover, after "Take photo", only when the chooser is non-null. A chosen photo sets `coverUrl`, as the taker does.

- [ ] **Step 1: Write failing tests.** Build `PlatformServices` like `ScreenshotTest` does, with an identity fake shrinker, a recording fake camera and a fake picker that returns `byteArrayOf(1)`. Show the book page for a book without a cover.
  - `choosingFromPhotosSetsTheCover`: tap "Add a cover photo", then "Choose from photos". Assert the book's stored `coverUrl` starts with `cover:`, and the fake camera was never called.
  - `withoutAPickerTheCameraOpensDirectly`: set `photoPicker = null` and tap "Add a cover photo". Assert the fake camera was called and no "Choose from photos" node exists.
  - `theEditFormOffersChoosePhoto`: on the edit form, assert "Choose photo" is shown with a picker, and absent without one.
  - In `ScreenshotTest`, add `coverPhotoMenuLight` and `coverPhotoMenuDark`: the book page with the menu open.
- [ ] **Step 2: Run.** `timeout 1200 ./gradlew :androidApp:testDebugUnitTest --tests '*CoverFromPhotosUiTest*' -q`. Expected: FAIL.
- [ ] **Step 3: Implement** the header menu, the detail-screen wiring and the edit-form button.
- [ ] **Step 4: Run** the full gate, both commands. Look at the two screenshots. Expected: all green, and the menu reads well in light and dark.
- [ ] **Step 5: Commit:** "Cover photos: choose an existing photo".
