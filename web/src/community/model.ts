import type { Status } from '../sync/model'

/** Who can see a person's activity. */
export type Visibility = 'friends' | 'public'

/** How the signed-in person relates to someone else. */
export type Relationship = 'none' | 'friends' | 'sent' | 'received'

export type ReportReason = 'spam' | 'harassment' | 'inappropriate' | 'impersonation' | 'other'

export const REPORT_REASONS: { id: ReportReason; label: string }[] = [
  { id: 'spam', label: 'Spam' },
  { id: 'harassment', label: 'Harassment' },
  { id: 'inappropriate', label: 'Inappropriate content' },
  { id: 'impersonation', label: 'Pretending to be someone else' },
  { id: 'other', label: 'Something else' },
]

/** The signed-in person's own profile. */
export type Profile = {
  id: string
  username: string
  displayName: string
  avatarUrl: string | null
  visibility: Visibility
  /** Whether book changes are shared automatically. */
  shareActivity: boolean
}

/** Someone else, as lists and profile pages show them. */
export type Person = {
  id: string
  username: string
  displayName: string
  avatarUrl: string | null
  visibility: Visibility
}

export type SearchResult = { person: Person; relationship: Relationship }

export type SuggestedReader = {
  person: Person
  sharedBooks: number
  mutualFriends: number
  mutualNames: string[]
  topTitle: string | null
}

export type ActivityKind = 'started' | 'finished' | 'want_to_read' | 'want_to_buy' | 'rated' | 'reviewed' | 'progress'

export type FeedScope = 'friends' | 'everyone'

export type FeedBook = {
  shareId: string
  isbn: string | null
  title: string
  authors: string
  coverUrl: string | null
  blurb: string | null
  pages: number | null
  currentPage: number | null
}

export type FeedEntry = {
  id: number
  actor: Person
  kind: ActivityKind
  rating: number | null
  progress: number | null
  /** When it happened, in ms. */
  createdAt: number
  /** As the server sent it: the next page starts from this exact value. */
  createdAtText: string
  book: FeedBook
  review: string | null
  likeCount: number
  likedByMe: boolean
}

export type FeedCursor = { createdAt: string; id: number }

/** [next] is null when there are no more entries. */
export type FeedPage = { entries: FeedEntry[]; next: FeedCursor | null }

export const FEED_PAGE_SIZE = 20

/** Reviews per page of `book_reviews` (the server function's default). */
export const BOOK_REVIEWS_PAGE = 10

/** The longest note on a book suggestion, and how many friends one can go to (the server's limits). */
export const SUGGESTION_NOTE_MAX = 280
export const SUGGESTION_MAX_FRIENDS = 20

/** A book a friend suggested to the signed-in person. [seen]: they have looked at it (the badge counts the others). */
export type BookSuggestion = {
  id: number
  sender: Person
  book: FeedBook
  note: string
  createdAt: number
  seen: boolean
}

export type FriendReading = { person: Person; book: FeedBook; progress: number | null; updatedAt: number }

export type FriendWithBook = {
  person: Person
  status: Status
  rating: number | null
  progress: number | null
  review: string
}

/** [perScore]: how many gave 5, 4, 3, 2 and 1 stars, in that order. */
export type BookScore = { ratings: number; average: number | null; perScore: number[]; reviews: number }

export const NO_SCORE: BookScore = { ratings: 0, average: null, perScore: [0, 0, 0, 0, 0], reviews: 0 }

export type BookReview = { person: Person; rating: number | null; review: string; updatedAt: number }
