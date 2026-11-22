import type { SupabaseClient } from '@supabase/supabase-js'
import type { Status } from '../sync/model'
import {
  FEED_PAGE_SIZE, NO_SCORE, type ActivityKind, type BookReview, type BookScore, type BookSuggestion, type FeedBook, type FeedCursor,
  type FeedEntry, type FeedPage, type FeedScope, type FriendReading, type FriendWithBook, type Person, type Profile,
  type Relationship, type ReportReason, type SearchResult, type SuggestedReader, type Visibility,
} from './model'

export type CommunityErrorKind = 'offline' | 'username_taken' | 'not_available' | 'rejected' | 'too_many' | 'other'

/** A failure the community pages can explain. Every call of [CommunityApi] throws only this. */
export class CommunityError extends Error {
  constructor(readonly kind: CommunityErrorKind, message: string) {
    super(message)
    this.name = 'CommunityError'
  }
}

/** A short explanation for a failed community call. */
export function communityMessage(e: unknown): string {
  switch (e instanceof CommunityError ? e.kind : 'other') {
    case 'offline':
      return "You're offline. Try again when you're connected."
    case 'username_taken':
      return 'That username is taken.'
    case 'not_available':
      return "This profile isn't available."
    case 'too_many':
      return "You've suggested a lot of books today. Try again tomorrow."
    default:
      return 'Something went wrong. Please try again.'
  }
}

type DbError = { message: string; code?: string }

function toError(e: unknown): CommunityError {
  if (e instanceof CommunityError) return e
  const error = e as Partial<DbError> | null
  const code = error?.code
  if (code === '23505') return new CommunityError('username_taken', 'username taken')
  if (code === 'P0002') return new CommunityError('not_available', 'not available')
  if (code === '22023') return new CommunityError('rejected', 'rejected')
  if (code === 'P0429') return new CommunityError('too_many', 'too many')
  const message = error?.message ?? ''
  // How fetch fails with no connection (Chrome, Safari, Firefox), directly or as supabase-js passes it on.
  if (e instanceof TypeError || /failed to fetch|load failed|networkerror/i.test(message)) {
    return new CommunityError('offline', 'offline')
  }
  return new CommunityError('other', message || 'error')
}

/** [text] without half an emoji (from a cut at a length limit), which the server would refuse. */
function wellFormed(text: string): string {
  return text.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]|[\uD800-\uDFFF]/g, (m) => (m.length === 2 ? m : ''))
}

async function run<T>(call: () => PromiseLike<{ data: T; error: DbError | null }>): Promise<T> {
  let result: { data: T; error: DbError | null }
  try {
    result = await call()
  } catch (e) {
    throw toError(e)
  }
  if (result.error) throw toError(result.error)
  return result.data
}

const STATUS_OF: Record<string, Status> = { reading: 'READING', read: 'READ', to_read: 'TO_READ', to_buy: 'WISHLIST' }

type ProfileRow = {
  id: string
  username: string
  display_name: string
  avatar_path?: string | null
  visibility?: string
  share_activity?: boolean
}

type PersonFields = { username: string; display_name: string; avatar_path?: string | null }

type FeedRow = PersonFields & {
  id: number
  kind: string
  rating: number | null
  progress: number | null
  created_at: string
  actor_id: string
  book_id: string
  isbn: string | null
  title: string
  authors: string
  cover_url: string | null
  review: string | null
  like_count: number | null
  liked_by_me: boolean | null
  book_blurb: string | null
  book_pages: number | null
  book_current_page: number | null
}

type FriendshipRow = { requester: string; addressee: string; status: string }

const COVERS_PAGE = 100

/** How [me] relates to [other], from the friendship rows visible to me. */
export function relationshipFrom(rows: FriendshipRow[], me: string, other: string): Relationship {
  const row = rows.find((r) => (r.requester === me && r.addressee === other) || (r.requester === other && r.addressee === me))
  if (!row) return 'none'
  if (row.status === 'accepted') return 'friends'
  return row.requester === me ? 'sent' : 'received'
}

/** The community on the server, the same calls the phone makes (`SupabaseSocial.kt`). */
export function createCommunityApi(client: SupabaseClient, me: string) {
  const avatarUrl = (path: string | null | undefined): string | null =>
    path ? client.storage.from('avatars').getPublicUrl(path).data.publicUrl : null

  const person = (id: string, row: PersonFields & { visibility?: string }): Person => ({
    id,
    username: row.username,
    displayName: row.display_name,
    avatarUrl: avatarUrl(row.avatar_path),
    visibility: row.visibility === 'public' ? 'public' : 'friends',
  })

  const profile = (row: ProfileRow): Profile => ({
    ...person(row.id, row),
    shareActivity: row.share_activity ?? true,
  })

  const entry = (row: FeedRow): FeedEntry => ({
    id: row.id,
    actor: person(row.actor_id, row),
    kind: row.kind as ActivityKind,
    rating: row.rating ?? null,
    progress: row.progress ?? null,
    createdAt: Date.parse(row.created_at),
    createdAtText: row.created_at,
    book: {
      shareId: row.book_id, isbn: row.isbn ?? null, title: row.title, authors: row.authors, coverUrl: row.cover_url ?? null,
      blurb: row.book_blurb ?? null, pages: row.book_pages ?? null, currentPage: row.book_current_page ?? null,
    },
    review: row.review ?? null,
    likeCount: row.like_count ?? 0,
    likedByMe: row.liked_by_me ?? false,
  })

  const page = (rows: FeedRow[] | null): FeedPage => {
    const entries = (rows ?? []).map(entry)
    const last = entries[entries.length - 1]
    return { entries, next: last && entries.length >= FEED_PAGE_SIZE ? { createdAt: last.createdAtText, id: last.id } : null }
  }

  const cursorArgs = (cursor: FeedCursor | null) => ({
    before_time: cursor?.createdAt ?? null,
    before_id: cursor?.id ?? null,
    max: FEED_PAGE_SIZE,
  })

  const friendships = async () =>
    (await run(() => client.from('friendships').select('requester, addressee, status'))) as FriendshipRow[]

  const peopleByIds = async (ids: string[]): Promise<Person[]> => {
    if (ids.length === 0) return []
    const rows = (await run(() => client.from('profiles').select('*').in('id', ids))) as ProfileRow[]
    return rows.map((r) => person(r.id, r))
  }

  const removeCoverPhotos = async () => {
    const bucket = client.storage.from('covers')
    const names: string[] = []
    for (;;) {
      const { data, error } = await bucket.list(me, { limit: COVERS_PAGE, offset: names.length })
      if (error) {
        // No covers bucket: nothing to remove.
        if (error.message.toLowerCase().includes('not found')) break
        throw toError(error)
      }
      names.push(...(data ?? []).map((f) => `${me}/${f.name}`))
      if ((data ?? []).length < COVERS_PAGE) break
    }
    for (let i = 0; i < names.length; i += COVERS_PAGE) {
      const { error } = await bucket.remove(names.slice(i, i + COVERS_PAGE))
      if (error) throw toError(error)
    }
  }

  return {
    me,

    // ---- my profile ----

    /** The signed-in person's profile; null until they choose a username. */
    async myProfile(): Promise<Profile | null> {
      const row = (await run(() => client.from('profiles').select('*').eq('id', me).maybeSingle())) as ProfileRow | null
      return row ? profile(row) : null
    },

    async isUsernameAvailable(username: string): Promise<boolean> {
      const rows = (await run(() => client.from('profiles').select('id').eq('username', username))) as unknown[]
      return rows.length === 0
    },

    async createProfile(username: string, displayName: string): Promise<Profile> {
      const row = await run(() =>
        client.from('profiles').insert({ id: me, username, display_name: displayName.trim() }).select().single(),
      )
      return profile(row as ProfileRow)
    },

    async updateProfile(displayName: string, visibility: Visibility): Promise<Profile> {
      const row = await run(() =>
        client.from('profiles').update({ display_name: displayName.trim(), visibility }).eq('id', me).select().single(),
      )
      return profile(row as ProfileRow)
    },

    async uploadAvatar(jpeg: Blob): Promise<Profile> {
      const old = (await run(() => client.from('profiles').select('avatar_path').eq('id', me).single())) as {
        avatar_path: string | null
      }
      // A new file name each time, so that image caches never show the old photo.
      const path = `${me}/avatar-${Date.now()}.jpg`
      const bucket = client.storage.from('avatars')
      const uploaded = await bucket.upload(path, jpeg, { upsert: false, contentType: 'image/jpeg' }).catch((e: unknown) => {
        throw toError(e)
      })
      if (uploaded.error) throw toError(uploaded.error)
      const row = await run(() => client.from('profiles').update({ avatar_path: path }).eq('id', me).select().single())
      if (old.avatar_path) await bucket.remove([old.avatar_path]).catch(() => undefined)
      return profile(row as ProfileRow)
    },

    /** Turns sharing on or off; off also removes everything shared, on the server. */
    async setShareActivity(enabled: boolean): Promise<Profile> {
      await run(() => client.rpc('set_share_activity', { enabled }))
      const row = await run(() => client.from('profiles').select('*').eq('id', me).single())
      return profile(row as ProfileRow)
    },

    /** Deletes the account for good, with its profile, friends, shared books and cloud library. */
    async deleteAccount(): Promise<void> {
      const row = (await run(() => client.from('profiles').select('avatar_path').eq('id', me).maybeSingle())) as {
        avatar_path: string | null
      } | null
      if (row?.avatar_path) await client.storage.from('avatars').remove([row.avatar_path]).catch(() => undefined)
      // The library's cover photos go first: they would outlive the account, or keep it from being deleted.
      try {
        await removeCoverPhotos()
      } catch (e) {
        throw toError(e)
      }
      await run(() => client.rpc('delete_my_account'))
    },

    // ---- people ----

    /** Someone else; null when they don't exist or a block hides them. */
    async person(id: string): Promise<Person | null> {
      const row = (await run(() => client.from('profiles').select('*').eq('id', id).maybeSingle())) as ProfileRow | null
      return row ? person(row.id, row) : null
    },

    async friends(): Promise<Person[]> {
      const rows = await friendships()
      return peopleByIds(rows.filter((r) => r.status === 'accepted').map((r) => (r.requester === me ? r.addressee : r.requester)))
    },

    async incomingRequests(): Promise<Person[]> {
      const rows = await friendships()
      return peopleByIds(rows.filter((r) => r.status === 'pending' && r.addressee === me).map((r) => r.requester))
    },

    async outgoingRequests(): Promise<Person[]> {
      const rows = await friendships()
      return peopleByIds(rows.filter((r) => r.status === 'pending' && r.requester === me).map((r) => r.addressee))
    },

    /** At least 2 characters after trimming, or an empty list with no call. */
    async search(query: string): Promise<SearchResult[]> {
      const q = query.trim()
      if (q.length < 2) return []
      const rows = ((await run(() => client.rpc('search_profiles', { query: q, max: 20 }))) ?? []) as ProfileRow[]
      if (rows.length === 0) return []
      const links = await friendships()
      return rows.map((r) => ({ person: person(r.id, r), relationship: relationshipFrom(links, me, r.id) }))
    },

    async relationship(id: string): Promise<Relationship> {
      return relationshipFrom(await friendships(), me, id)
    },

    /** Sends a request, or accepts theirs if they already asked. */
    async sendRequest(id: string): Promise<Relationship> {
      const status = await run(() => client.rpc('send_friend_request', { target: id }))
      return status === 'accepted' ? 'friends' : 'sent'
    },

    async respond(requester: string, accept: boolean): Promise<void> {
      await run(() => client.rpc('respond_to_friend_request', { requester, accept }))
    },

    async cancelRequest(id: string): Promise<void> {
      await run(() => client.from('friendships').delete().eq('requester', me).eq('addressee', id).eq('status', 'pending'))
    },

    async removeFriend(id: string): Promise<void> {
      await run(() =>
        client.from('friendships').delete().or(`and(requester.eq.${me},addressee.eq.${id}),and(requester.eq.${id},addressee.eq.${me})`),
      )
    },

    async suggestedReaders(): Promise<SuggestedReader[]> {
      const rows = ((await run(() => client.rpc('suggested_readers'))) ?? []) as (PersonFields & {
        id: string
        shared_books?: number
        mutual_friends?: number
        mutual_names?: string[]
        top_title?: string | null
      })[]
      return rows.map((r) => ({
        person: person(r.id, r),
        sharedBooks: r.shared_books ?? 0,
        mutualFriends: r.mutual_friends ?? 0,
        mutualNames: r.mutual_names ?? [],
        topTitle: r.top_title ?? null,
      }))
    },

    // ---- safety ----

    async block(id: string): Promise<void> {
      await run(() => client.rpc('block_user', { target: id }))
    },

    async unblock(id: string): Promise<void> {
      await run(() => client.from('blocks').delete().eq('blocker', me).eq('blocked', id))
    },

    async blockedPeople(): Promise<Person[]> {
      const rows = ((await run(() => client.rpc('my_blocked_profiles'))) ?? []) as ProfileRow[]
      return rows.map((r) => person(r.id, r))
    },

    /** [activity]: the feed entry being reported, if any. */
    async report(id: string, reason: ReportReason, details: string | null, activity: number | null = null): Promise<void> {
      await run(() =>
        client.from('reports').insert({ reporter: me, reported: id, reason, details: wellFormed(details?.trim() ?? '') || null, activity }),
      )
    },

    // ---- the feed ----

    async feed(scope: FeedScope, cursor: FeedCursor | null = null): Promise<FeedPage> {
      return page((await run(() => client.rpc('feed', { scope, ...cursorArgs(cursor) }))) as FeedRow[] | null)
    },

    /** One person's entries; empty when the caller may not see them. */
    async personActivity(id: string, cursor: FeedCursor | null = null): Promise<FeedPage> {
      return page((await run(() => client.rpc('person_activity', { person: id, ...cursorArgs(cursor) }))) as FeedRow[] | null)
    },

    async like(activity: number): Promise<void> {
      await run(() => client.rpc('like', { activity }))
    },

    async unlike(activity: number): Promise<void> {
      await run(() => client.rpc('unlike', { activity }))
    },

    async likers(activity: number): Promise<Person[]> {
      const rows = ((await run(() => client.rpc('likers', { activity }))) ?? []) as ProfileRow[]
      return rows.map((r) => person(r.id, r))
    },

    async friendsReading(): Promise<FriendReading[]> {
      const rows = ((await run(() => client.rpc('friends_reading'))) ?? []) as (PersonFields & {
        actor_id: string
        book_id: string
        isbn: string | null
        title: string
        authors: string
        cover_url: string | null
        pages: number | null
        current_page: number | null
        progress: number | null
        updated_at: string
      })[]
      return rows.map((r) => {
        const book: FeedBook = {
          shareId: r.book_id, isbn: r.isbn ?? null, title: r.title, authors: r.authors, coverUrl: r.cover_url ?? null,
          blurb: null, pages: r.pages ?? null, currentPage: r.current_page ?? null,
        }
        return { person: person(r.actor_id, r), book, progress: r.progress ?? null, updatedAt: Date.parse(r.updated_at) }
      })
    },

    // ---- a book ----

    async friendsWithBook(isbn: string): Promise<FriendWithBook[]> {
      const rows = ((await run(() => client.rpc('friends_with_book', { p_isbn: isbn }))) ?? []) as (PersonFields & {
        actor_id: string
        status: string
        rating: number | null
        progress: number | null
        review: string | null
      })[]
      return rows.map((r) => ({
        person: person(r.actor_id, r),
        status: STATUS_OF[r.status] ?? 'TO_READ',
        rating: r.rating ?? null,
        progress: r.progress ?? null,
        review: r.review ?? '',
      }))
    },

    /** The community's scores for a book (readers the caller may see, not the caller). */
    async bookScore(isbn: string): Promise<BookScore> {
      const rows = ((await run(() => client.rpc('book_score', { isbn }))) ?? []) as {
        ratings?: number
        average?: number | string | null
        five?: number
        four?: number
        three?: number
        two?: number
        one?: number
        reviews?: number
      }[]
      const r = rows[0]
      if (!r) return NO_SCORE
      return {
        ratings: r.ratings ?? 0,
        average: r.average === null || r.average === undefined ? null : Number(r.average),
        perScore: [r.five ?? 0, r.four ?? 0, r.three ?? 0, r.two ?? 0, r.one ?? 0],
        reviews: r.reviews ?? 0,
      }
    },

    /** Readers' scores and reviews: written reviews first, newest first. */
    async bookReviews(isbn: string, skip = 0): Promise<BookReview[]> {
      const rows = ((await run(() => client.rpc('book_reviews', { isbn, skip }))) ?? []) as (PersonFields & {
        person_id: string
        rating: number | null
        review: string | null
        updated_at: string
      })[]
      return rows.map((r) => ({
        person: person(r.person_id, r),
        rating: r.rating ?? null,
        review: r.review ?? '',
        updatedAt: Date.parse(r.updated_at),
      }))
    },

    // ---- suggesting books to friends ----

    /** How many of [recipients] got it: those who already had this book from you don't. */
    async suggestBook(book: FeedBook, recipients: string[], note: string): Promise<number> {
      const p_book = {
        isbn: book.isbn, title: wellFormed(book.title), authors: wellFormed(book.authors), cover_url: book.coverUrl,
        pages: book.pages, blurb: book.blurb === null ? null : wellFormed(book.blurb),
      }
      return (await run(() => client.rpc('suggest_book', { p_book, p_recipients: recipients, p_note: wellFormed(note).trim() }))) as number
    },

    /** What friends suggested to the signed-in person and is still open, newest first. */
    async bookSuggestions(): Promise<BookSuggestion[]> {
      const rows = ((await run(() => client.rpc('my_book_suggestions'))) ?? []) as (PersonFields & {
        id: number
        sender_id: string
        isbn: string | null
        title: string
        authors: string
        cover_url: string | null
        pages: number | null
        blurb: string | null
        note: string | null
        created_at: string
        seen: boolean
      })[]
      return rows.map((r) => ({
        id: r.id,
        sender: person(r.sender_id, r),
        book: {
          shareId: `suggestion:${r.id}`, isbn: r.isbn ?? null, title: r.title, authors: r.authors, coverUrl: r.cover_url ?? null,
          blurb: r.blurb ?? null, pages: r.pages ?? null, currentPage: null,
        },
        note: r.note ?? '',
        createdAt: Date.parse(r.created_at),
        seen: r.seen,
      }))
    },

    /** The person has looked at these: only the ones on their screen. */
    async markSuggestionsSeen(ids: number[]): Promise<void> {
      await run(() => client.rpc('mark_book_suggestions_seen', { ids }))
    },

    /** The book was added or dismissed. */
    async closeSuggestion(id: number): Promise<void> {
      await run(() => client.rpc('close_book_suggestion', { suggestion: id }))
    },
  }
}

export type CommunityApi = ReturnType<typeof createCommunityApi>
