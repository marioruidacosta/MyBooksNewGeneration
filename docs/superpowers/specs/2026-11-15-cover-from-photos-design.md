# Cover from an existing photo: design

**Date:** 2026-11-15
**Status:** approved in conversation (short design, small change)

## Intent
When adding or changing a book's cover, the person can pick a photo already on the phone (a downloaded cover image,
a picture taken earlier) in addition to taking a new one with the camera.

## Today
- Book page: "Add a cover photo" (shown when the book has no cover) opens the camera.
- Edit form: "Take photo" under the cover opens the camera.
- Both use `rememberCoverPhotoTaker`: the photo is shrunk (`PhotoShrinker`), stored in `CoverStore` as a `cover:`
  reference, and synced/backed up like any cover photo.

## Design
- **Book page:** "Add a cover photo" opens a small menu: "Take photo" / "Choose from photos".
- **Edit form:** a "Choose photo" button next to "Take photo" under the cover.
- **Android:** the system photo picker (`PickVisualMedia`, images only, no permission). Cancelling changes nothing.
- **Same path as the camera:** the picked image is read, shrunk, stored in `CoverStore` and set as the cover, so it
  syncs and backs up exactly like a camera photo. An unreadable image shows "Couldn't use the photo: …".
- **iOS (call made in conversation):** keeps the camera only for now; the "choose" option is hidden there (the iOS
  picker needs Swift code that can't be built or tested from the Windows dev machine). It can follow later.

## Approaches
1. **System photo picker via the existing platform-services pattern (chosen).** A `CoverPhotoPicker` platform service
   beside `CoverCamera`, Android-only, reusing the shrink-and-store path. No permission, consistent with the camera.
2. Storage Access Framework document picker (`GetContent("image/*")`): works too, but shows a file browser rather than
   the photo gallery; the photo picker already falls back to it on old Android versions.
3. Reading the gallery directly (MediaStore + a custom grid): needs the photos permission and much more UI; rejected.

## Non-goals
- Cropping or editing the picked image.
- Picking a cover image from the web.
- Changing a cover that is already set from the book page (the edit form already covers that).

## Tests
- Picking a photo sets it as the cover (stored and referenced as `cover:`).
- Cancelling leaves the cover unchanged.
- An unreadable image shows the error message.
- Screenshots of the book-page menu, light and dark.
