# BookNerd: a book bot that suggests books, friends and writes abstracts

**Branch:** `feature/booknerd` (from `main`).

## Why

The user wants a bot in MyBooks, called **BookNerd**, that answers three requests: suggest books, suggest friends,
and write an abstract for a book from an idea the user gives it.

### Decisions taken with the user

- **No paid AI.** The user doesn't want to spend money: Claude Pro doesn't include the API, and the user's phone
  (Galaxy A51, Android 13) can't run Google's on-device model. BookNerd uses no language model at all.
- **A chat with buttons**: message bubbles, quick-reply chips, and simple keyword understanding of typed text in
  Portuguese and English.
- **Book suggestions** come from the app's existing suggestion engine.
- **Friend suggestions**: readers with similar taste and friends of friends, from a new server function.
- **Abstracts** come from a guided set of questions and hand-written templates (no AI).
- The conversation lasts while the app is open; nothing is saved.

### Success criteria

1. Typing "sugere-me um livro", "suggest a book", "quero novos amigos", "find me readers", "tenho uma ideia para um
   livro" or "write a blurb" starts the matching flow; anything else gets the three chips.
2. Book suggestions show 5 books with their reasons, each addable to To read or To buy; "More" shows the next 5.
3. Friend suggestions show up to 10 people with a reason; "Add friend" sends a request. Nobody blocked, already a
   friend, already requested, or the user themselves ever appears.
4. The abstract flow asks 5 questions and writes a 120–180-word abstract in the user's language, with no unfilled
   gaps for any genre and tone; "Another version" gives different wording.
5. Everything except friend suggestions works offline and without an account.

### Out of scope

Any AI or language model; saving conversations; other intents (stats, reading goals, …); voice input.

## What the user gets

### Opening BookNerd

A BookNerd icon (a small bot face) in the Home top bar opens the BookNerd screen: a chat that starts with
"Olá! I'm BookNerd. I can suggest books, find you fellow readers, or write an abstract for a book idea." and three
chips: **Suggest books**, **Suggest friends**, **Write an abstract**. A text field at the bottom takes typed
messages.

### Understanding typed messages

Text is lower-cased and accents removed, then matched against keyword lists (whole words or word starts):

- **Books**: livro, livros, ler, leitura, sugere, sugestao, recomenda, book, books, read, suggest, recommend.
- **Friends**: amigo, amigos, amiga, pessoas, leitores, gente, friend, friends, people, readers, buddies.
- **Abstract**: resumo, sinopse, ideia, enredo, historia, abstract, blurb, synopsis, idea, plot, story.

The intent with the most hits wins; "friend" beats "book" in "find friends who read books" because friend words are
checked first when both match equally. No hit: "I can help with three things:" and the chips.

**Language**: Portuguese when the message has a Portuguese keyword or Portuguese accented letters (ã, õ, ç, é, ê,
á, à, ó, ô, í, ú), otherwise English. BookNerd's replies in that flow use that language; chips stay in English
(the app's language).

### Book suggestions

BookNerd answers "Here are some books you might like:" / "Aqui vão alguns livros que podes gostar:" followed by up to
5 book cards from the existing suggestion engine (cover, title, author, reason line, **To read**, **To buy**), then
chips **More** and **Something else** (back to the three chips). "More" shows the next 5; when there are no more:
"That's all I have for now." No suggestions at all (empty library): "Add a few books you loved and I'll find more
like them."

### Friend suggestions

Requires a community account. Signed out: "Join the community to get friend suggestions" with a **Join** button.
Otherwise BookNerd calls `suggested_readers()` and shows up to 10 person cards (avatar, name, reason, **Add friend**,
**View profile**). Reasons:

- Similar taste: "Also loved Os Maias and 2 more" / "Também adorou Os Maias e mais 2".
- Friends of friends: "Friend of Ana and Rui" / "Amigo de Ana e Rui" ("and 2 more" beyond two names).

"Add friend" sends a friend request and turns into "Request sent". No results: "I couldn't find anyone yet — invite
your friends!" Offline or failing: "Couldn't reach the community." with **Retry**.

### Abstracts

BookNerd asks, one at a time (each answer shown as the user's bubble):

1. **Genre** — chips: Romance, Mystery, Fantasy, Science fiction, Historical, Thriller, Drama, Comedy (or typed).
2. **Main character** — "Who is the main character? (a name and a few words)".
3. **Setting** — "Where and when does it happen?".
4. **What goes wrong** — "What goes wrong, or what do they want?".
5. **Tone** — chips: Light, Dark, Moving, Funny, Suspenseful.

Then it writes the abstract (120–180 words) from templates chosen by genre and tone, filling in the answers, in the
language of the conversation. Buttons: **Another version** (a different template for the same answers), **Copy**,
**Share**, **Start over**. An empty answer gets "Tell me a little more?" and the question again.

### Privacy

Book suggestions and abstracts run on the phone and send nothing. Friend suggestions use only what the community
already shows: public readers' shared books and ratings, and friendships of the user's own friends.

## How it fits

### Server (`supabase/migrations/2026110900_suggested_readers.sql`)

`suggested_readers(max int default 10)` returns `(id, username, display_name, avatar_path, reason text,
shared_books int, mutual_friends int, mutual_names text[], top_title text)`, `security definer`,
`require_user()`, granted to `authenticated` only. Candidates exclude: the caller; anyone in a `friendships` row with
the caller (any status); anyone blocked either way. Two sources, merged per person:

- **Similar taste**: public profiles who share activity, with `shared_books` rated 4–5 whose ISBN matches a book the
  caller shares rated 4–5; `top_title` is the most recent such match.
- **Friends of friends**: people with an accepted friendship to one of the caller's friends; `mutual_names` are up to
  2 of those friends' display names, `mutual_friends` the count.

Ordered by `shared_books + 2 * mutual_friends` descending, then display name; at most `max` (capped at 20). pgTAP
tests in `supabase/tests/suggested_readers_test.sql`.

### Phone (shared, commonMain, `ui/booknerd/`)

- **`Intent.kt`**: `enum class BotIntent { BOOKS, FRIENDS, ABSTRACT }`, `enum class BotLanguage { PT, EN }`,
  `fun understand(text: String): Pair<BotIntent?, BotLanguage>`.
- **`AbstractWriter.kt`**: `data class IdeaAnswers(genre, character, setting, conflict, tone)`,
  `fun writeAbstract(answers: IdeaAnswers, language: BotLanguage, variant: Int): String` — templates per genre ×
  language (at least 3 variants each), tone sentences, word count 120–180.
- **`BookNerdViewModel.kt`**: the conversation (`List<BotMessage>`: bot text, user text, book cards, person cards,
  abstract), the current flow state, chips; uses `SuggestionEngine`, `SocialServices?` (friends, `suggestedReaders`),
  `LibraryShelf` (adds), and sends friend requests through `FriendsService`.
- **`BookNerdScreen.kt`**: bubbles, cards, chips, text field; Copy/Share through `PlatformServices`.
- `FriendsService` gains `suggestedReaders(): List<SuggestedReader>` (default `emptyList()`), with the Supabase
  implementation and `FakeSocial`.
- Route `Routes.BOOKNERD = "booknerd"`; Home top bar icon.

## Testing

- `understand`: each keyword list in both languages, accents and capitals ignored, ties, no match, language choice.
- `writeAbstract`: every genre × tone × language × variant has no `{placeholder}` left, contains the character and
  setting, and is 120–180 words; variants differ.
- `BookNerdViewModel`: each flow's steps with fakes (books with "More" and the end; friends signed out, empty,
  failing + Retry, Add friend; abstract questions, empty answer, Another version, Start over; unknown text).
- pgTAP: exclusions (self, friends, pending both ways, blocked both ways), private-profile readers not matched by
  taste, reasons and order, the cap, anon refused.
- Screenshots: BookNerd with book cards (light), with an abstract (dark).
