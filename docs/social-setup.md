# Setting up the MyBooks community (Supabase)

The community features (accounts, friends, requests, block and report) run on a free
[Supabase](https://supabase.com) project. Until it's configured, the app hides them and behaves
exactly as before.

All the rules (who can see what, who can accept a request, blocking, account deletion) are in
`supabase/migrations/`. They're tested automatically by the **Supabase** GitHub workflow
(`supabase/tests/`).

## 1. Create the project

1. Sign up at <https://supabase.com> and create a **New project**:
   - Name: `mybooks`
   - Region: **West EU (Frankfurt)**, or the one nearest to your users
   - Database password: generate one and keep it in a password manager (you need it in step 3)
2. When it's ready, open **Project Settings → API** and note:
   - **Project URL** (e.g. `https://abcdefgh.supabase.co`)
   - **anon public** key (a long `eyJ…` string). It's meant to be built into apps; the database
     rules protect the data.
   - Never use or share the **service_role** key.

## 2. Turn on sign-in with an email code

1. **Authentication → Sign In / Providers → Email**: keep **Email** enabled and turn **Confirm email** on.
2. **Authentication → Emails → Templates → Magic Link**: replace the body so it shows the code,
   for example:

   ```html
   <h2>Your MyBooks code</h2>
   <p>Enter this code in the app: <strong>{{ .Token }}</strong></p>
   <p>It expires in one hour. If you didn't ask for it, ignore this email.</p>
   ```

   Do the same for the **Confirm signup** template, which is used the first time someone signs in.
3. **Authentication → Sign In / Providers → Email**: leave **Email OTP Length** at **6**. The app
   expects exactly 6 digits, so another length makes sign-in impossible.
4. **Authentication → URL Configuration**: the Site URL can stay as it is; the app doesn't use links.

## 3. Create the tables and rules

From the MyBooks folder on your PC (Node.js is already in `C:\Users\mcosta\devtools`):

```powershell
cd C:\Users\mcosta\GIT\MyBooks             # must run here: db push sends supabase\migrations from the current folder
$env:Path = "C:\Users\mcosta\devtools\node-v18.20.8-win-x64;$env:Path"
# The company network (Zscaler) inspects HTTPS; this makes the CLI trust the same certificates
# as Windows and the browser. Without it: "failed to execute http request: Transport error".
$env:NODE_USE_SYSTEM_CA = "1"
npx supabase login                         # opens the browser once
npx supabase link --project-ref abcdefgh   # the part of your Project URL before .supabase.co
npx supabase db push --remote              # asks for the database password from step 1
```

`db push` applies everything in `supabase/migrations/`. Since Supabase CLI v3, `db push` targets the local
database unless you add `--remote`; without it you get `connect ECONNREFUSED 127.0.0.1:54322`. Run it again whenever a new migration is
added.

### 3b. Activity feed (social part 2)

Part 2 adds `supabase/migrations/2026110200_social_part2.sql` (shared books, the activity feed and
likes). Apply it once, the same way:

```powershell
cd C:\Users\mcosta\GIT\MyBooks
$env:Path = "C:\Users\mcosta\devtools\node-v18.20.8-win-x64;$env:Path"
$env:NODE_USE_SYSTEM_CA = "1"
npx supabase db push --remote              # lists 2026110200_social_part2.sql; answer Y
```

Until it's applied, the Activity screen shows an error and book changes simply wait on the phone;
they're uploaded as soon as the server has the new functions.

### 3c. Community feed, evolved

`supabase/migrations/2026110800_community_feed.sql` adds each shared book's blurb, page count and current
page, and the "Friends are reading" and "Friends who have this book" functions. It lives in this repository
(MyBooksNewGeneration), so push from here. **From now on, push migrations only from MyBooksNewGeneration:** the
MyBooks folder doesn't have this migration, and `db push` there would refuse once the server has it.

```powershell
cd C:\Users\mcosta\GIT\MyBooksNewGeneration
$env:Path = "C:\Users\mcosta\devtools\node-v22.23.4-win-x64;$env:Path"
$env:NODE_USE_SYSTEM_CA = "1"
npx supabase link --project-ref abcdefgh   # once per folder; the part of your Project URL before .supabase.co
npx supabase db push --remote              # lists 2026110800_community_feed.sql; answer Y
```

Until it's applied the app works as before: cards have no blurb or pages, and the new sections stay hidden.

### 3d. BookNerd friend suggestions

`supabase/migrations/2026110900_suggested_readers.sql` adds `suggested_readers()`, which BookNerd uses to suggest
readers with similar taste and friends of friends. Apply it like 3c: `npx supabase db push --remote` from
MyBooksNewGeneration, or, on a network that resets database connections, paste the file into the SQL Editor, run it,
then record it:

```sql
insert into supabase_migrations.schema_migrations (version, name, statements)
values ('2026110900', 'suggested_readers', array[]::text[]);
```

Until it's applied, BookNerd says it couldn't reach the community when asked for friends; books and abstracts work.

### 3e. Library sync

`supabase/migrations/2026111000_library_sync.sql` adds the private library tables (books, quotes, challenges, ebooks,
bookmarks, preferences) and the functions `sync_push` and `sync_pull` that the phone's cloud sync calls, plus the
Realtime ping that tells your other devices to pull. Apply it like 3d: `npx supabase db push --remote` from
MyBooksNewGeneration, or, on a network that resets database connections, paste the file into the SQL Editor, run it,
then record it:

```sql
insert into supabase_migrations.schema_migrations (version, name, statements)
values ('2026111000', 'library_sync', array[]::text[]);
```

Until it's applied nothing uses it: the app keeps its library on the phone only.

## 4. Give the apps the URL and key

- **Android** (your PC): add to `local.properties`, then rebuild:

  ```properties
  SUPABASE_URL=https://abcdefgh.supabase.co
  SUPABASE_ANON_KEY=eyJ...
  ```

- **iPhone** (cloud build): on GitHub → *Settings → Secrets and variables → Actions*, add the
  secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY` with the same values.

After that, the **Friends** icon appears in the library's top bar, and **Settings** shows
*Join the MyBooks community*.

## 5. Before opening it to everyone

- **Email sending**: Supabase's built-in email sends only a few messages per hour, which is fine
  for testing with friends. For a public launch, add your own SMTP sender in
  **Authentication → Emails → SMTP Settings** (Resend, Brevo and Postmark all have free tiers).
- **Privacy policy**: both app stores require a public page saying what you store (email,
  username, name, photo, friendships, reports) and how to delete it (Settings → Account →
  Delete account in the app).
- **Reports**: read them in **Table Editor → reports**. To act on one, block or delete the user
  from **Authentication → Users**.
