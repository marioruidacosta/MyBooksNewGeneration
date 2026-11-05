# Releasing MyBooks NG on Google Play

Everything below is ready except the Google Play developer account. With an account, a release takes about an hour the
first time, mostly filling in Play Console forms.

## What is ready

| | |
|---|---|
| App id | `com.mcosta.mybooks.ng` · versionCode 3 · versionName 2.0 |
| Bundle | `./gradlew :androidApp:bundleRelease` → `androidApp/build/outputs/bundle/release/androidApp-release.aab`, signed with the upload key |
| Upload key | `C:\Users\mcosta\.mybooks-keys\mybooks-ng-upload.jks`, alias `upload`; passwords in `local.properties` (`RELEASE_*`) |
| Upload key SHA-1 | `79:D1:0B:F4:54:3A:F2:91:87:19:B2:D0:A8:68:5A:05:12:BF:DB:E1` |
| Privacy policy | https://marioruidacosta.github.io/MyBooksNewGeneration/privacy.html |
| Account deletion | https://marioruidacosta.github.io/MyBooksNewGeneration/delete-account.html |

**Back up the upload key now**: copy the `.jks` file and the four `RELEASE_*` lines from `local.properties` to a
password manager. If the upload key is lost, Google can replace it (Play Console › App integrity), but that takes days.

## First release

1. Play Console › **Create app**: name *MyBooks NG*, app, free.
2. **App integrity**: keep *Play App Signing* on (Google keeps the app signing key; you upload with the upload key).
3. **Google Books key**: Play re-signs the app with Google's app signing key, so add that key's SHA-1 (App integrity ›
   App signing key certificate) to the Android restriction of the Google Books API key in Google Cloud Console, next
   to the upload key's. Otherwise book lookups through Google Books fail in the Play version.
4. **Testing › Internal testing** › Create release › upload the `.aab` › add yourself as a tester. Install it from the
   link and check it before going wider.
5. **App content**:
   - Privacy policy: the URL above.
   - App access: everything works without an account; the community needs an email sign-in code (give reviewers a
     test address if asked).
   - Ads: no ads.
   - Content rating: fill in the questionnaire (a reading app with optional user-written reviews visible to friends).
   - Target audience: 13 and over.
   - Data safety: see below.
   - Account deletion: the URL above.
6. **Store listing**: short and full description, icon (512×512), feature graphic (1024×500), at least two phone
   screenshots (the screen tests' pictures in the CI `screenshots` artifact are a starting point).
7. Each new upload needs a higher `versionCode` in `androidApp/build.gradle.kts`.

## Data safety answers

Data is encrypted in transit: **yes**. Users can request deletion: **yes** (in the app, and by email).
Nothing is collected without the community; with it, collected and not shared with third parties
(Supabase hosts it for the app):

| Data type | Collected | Optional | Why |
|---|---|---|---|
| Personal info › Email address | yes | yes (only with the community) | Account management |
| Personal info › Name (username, display name) | yes | yes | App functionality, account management |
| Photos (profile photo) | yes | yes | App functionality |
| App activity › Other user-generated content (shared books, scores, reviews, likes) | yes | yes | App functionality |
| App activity › Other actions (friends, blocks, reports) | yes | yes | App functionality |

Not collected: location, contacts, financial info, device or other IDs, analytics, crash logs. Book lookups send an
ISBN or a title and author to Google Books, Open Library and the Biblioteca Nacional de Portugal, with nothing that
identifies the user. Barcodes and page photos are read on the phone.

## Optional: signed bundles from CI

The Android workflow builds the release bundle on every run. On `main` it also signs it and keeps it as the
`play-bundle` artifact when these repository secrets exist. The app's other secrets are built in from the repository
secrets too: `GOOGLE_BOOKS_API_KEY` is set; `SUPABASE_URL` and `SUPABASE_ANON_KEY` are not yet, so add them first or
the CI bundle has no community (the bundle built on this computer has everything, from `local.properties`).

```sh
base64 -w0 /c/Users/mcosta/.mybooks-keys/mybooks-ng-upload.jks | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_STORE_PASSWORD   # paste the value from local.properties
gh secret set RELEASE_KEY_ALIAS --body upload
gh secret set RELEASE_KEY_PASSWORD     # paste the value from local.properties
```
