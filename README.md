# MyBooks NG

The new generation of [MyBooks](https://github.com/marioruidacosta/MyBooks): the same app with a reworked look and reading flow, installed side by side with MyBooks (app id `com.mcosta.mybooks.ng`). "New in Portugal" reads the lists published by the MyBooks repository.


A personal book library for Android and iPhone. Scan a book's barcode to add it, give it a 1–5 star score,
keep notes, and track every book as **Reading** (with start date and progress), **To read** (you own it),
**Read** or **To buy**.
All data stays on the phone; you can export/import it as a .zip backup from Settings.

## Features

- **Scan barcode** (Add button → Scan barcode): reads the ISBN on the back cover and fills in title,
  author, cover, publisher, year and pages. Sources, in order: **BNP / PORBASE** (Biblioteca Nacional
  de Portugal), **Google Books**, **Open Library**. If the first source to find the book has no cover,
  the others are asked for one.
  Scanning a book you already have opens it. Not found / offline → enter the details by hand.
- **Library** tabs: All / Reading / To read / Read / To buy, plus search by title or author.
- **Reading**: the start date is recorded when a book moves to Reading (tap *Change* to edit it);
  update progress by page number or percentage. Reading books show a progress bar in the list.
- **Book page**: change status, tap stars to rate (tap the same star again to clear), write notes
  (saved automatically). Marking a book Read records the date.
- **Cover photo**: when no cover is found, tap the empty cover on the book page (or *Take photo* in
  the edit form) to photograph the book with the phone's camera app. Photos are shrunk to about
  1000 px and stored privately in the app.
- **Community** (optional, needs a Supabase project, see [docs/social-setup.md](docs/social-setup.md)):
  sign in with an email code, choose a username, find people, send and accept friend requests,
  block and report, choose who sees your activity, delete your account. Your library stays on the phone.
- **Activity** (community): starting, finishing, rating, reviewing and reading progress are shared
  automatically (switch off in Account, or per book with *Don't share this book*). The Activity
  screen shows your friends' and public readers' entries; like them, see who liked, report, or add
  the book to your own library. Reviews are a separate field from your private notes.
- **New in Portugal**: each month's new books published in Portugal (from the National Library's legal
  deposit records), by genre, for the current month and the six before it. Add a book to your library, or
  open it at Bertrand, Almedina, Wook or Fnac. Built nightly by a GitHub Actions job; see
  [docs/releases-setup.md](docs/releases-setup.md).
- **Settings**: export the library, including cover photos, to a `.zip` file / import it back
  (older `.json` backups still import; merges, never deletes).

On Android, scanning uses Google's code scanner from Google Play services, so the phone needs Play
services (almost all Android phones have them). No camera permission is requested.

## Project layout

One Kotlin Multiplatform codebase:

- `shared/`: everything common to both apps (book data and rules, Room database, BNP / Google /
  Open Library lookups, backups, ViewModels and the Compose Multiplatform screens). Platform parts
  (barcode scanner, camera, file pickers, photo shrinking, SQLite driver) sit behind small interfaces
  in `androidMain` and `iosMain`.
- `androidApp/`: the Android app shell.
- `iosApp/`: the iPhone app shell (an XcodeGen project, generated and built in GitHub Actions).
  See [docs/ios-setup.md](docs/ios-setup.md) for installing it on an iPhone through TestFlight.

## Build

Requirements: JDK 17+ (built with Java 25) and the Android SDK (platform `android-37.0`).
Point the build at the SDK in `local.properties` (not committed):

```properties
sdk.dir=C\:\\Users\\<you>\\devtools\\android-sdk
```

### Google Books API key (recommended)

Without a key, Google Books requests share Google's anonymous daily quota, which is usually used up
(HTTP 429). The app then falls back to Open Library, which knows far fewer books. A free key gives
you your own quota (1,000 requests/day by default):

1. In [Google Cloud Console](https://console.cloud.google.com/) create a project (no billing needed).
2. *APIs & Services → Library* → enable **Books API**.
3. *APIs & Services → Credentials → Create credentials → API key*.
4. Optional but sensible: edit the key and under *API restrictions* allow only **Books API**.
   Leave *Application restrictions* at **None** — the app calls the API directly, so an
   "Android apps" restriction makes every request fail with "API key rejected".
5. Add it to `local.properties` and rebuild:

   ```properties
   GOOGLE_BOOKS_API_KEY=AIza...
   ```

The key is compiled into the APK, so don't share the APK publicly.

Then:

```sh
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest   # tests
./gradlew :androidApp:assembleDebug   # → androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Install on an Android phone

**Option A — USB cable (adb)**

1. On the phone: Settings → About phone → tap *Build number* 7 times to enable Developer options.
2. Settings → System → Developer options → enable *USB debugging*.
3. Connect the phone, accept the "Allow USB debugging?" prompt, then run:

   ```sh
   <sdk>/platform-tools/adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

**Option B — copy the APK**

Copy `androidApp-debug.apk` to the phone (cable, Google Drive, email…), open it from the Files app and
allow *Install unknown apps* for that app when asked.

Reinstalling a newer build with `adb install -r` (or over the top) keeps your library.
Uninstalling the app deletes it — export a backup first.
