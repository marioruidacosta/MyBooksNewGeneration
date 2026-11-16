import { supabase } from '../supabase'

/** A book as a lookup source describes it. `isbn` is null when the source has none (never an empty text). */
export type Found = {
  isbn: string | null
  title: string
  authors: string[]
  publisher: string | null
  year: number | null
  pages: number | null
  coverUrl: string | null
  /** Untrusted text from the source: show it as text only. */
  description: string | null
}

/** The two sources reached through the `lookup` function. */
export interface LookupApi {
  /** BNP by ISBN; null when it hasn't the book. */
  bnp(isbn: string): Promise<Found | null>
  /** Google Books for a query (`isbn:<isbn>` for one book). */
  google(q: string): Promise<Found[]>
}

export interface Lookup {
  /** The book for a valid ISBN-13, or null when no source has it. Throws NotFoundWithErrors when none has it and some could not be reached, and a plain error when none could be asked. */
  lookupIsbn(isbn: string): Promise<Found | null>
  searchTitle(q: string): Promise<Found[]>
}

/** As the app's `Isbn.normalize`: ISBN-10 or ISBN-13 (hyphens and spaces allowed) as 13 digits, or null. */
export function normalizeIsbn(raw: string): string | null {
  const s = raw.replace(/[-\s]/g, '').toUpperCase()
  const ean = (first12: string) =>
    (10 - (first12.split('').reduce((sum, c, i) => sum + Number(c) * (i % 2 === 0 ? 1 : 3), 0) % 10)) % 10
  if (s.length === 10) {
    if (!/^\d{9}[\dX]$/.test(s)) return null
    const sum = s.split('').reduce((acc, c, i) => acc + (10 - i) * (c === 'X' ? 10 : Number(c)), 0)
    if (sum % 11 !== 0) return null
    const body = `978${s.slice(0, 9)}`
    return body + String(ean(body))
  }
  if (s.length === 13 && /^97[89]\d{10}$/.test(s) && ean(s.slice(0, 12)) === Number(s[12])) return s
  return null
}

/** What a search text is, as an ISBN (the app's `isbnQuery`). */
export type IsbnQuery = { kind: 'valid'; isbn13: string } | { kind: 'invalid' } | { kind: 'none' }

export function isbnQuery(raw: string): IsbnQuery {
  const s = raw.replace(/[-\s]/g, '').toUpperCase()
  // Ten digits starting 978/979 are a 13-digit ISBN still being typed.
  if (/^97[89]\d{7}$/.test(s)) return { kind: 'none' }
  const valid = normalizeIsbn(raw)
  if (valid) return { kind: 'valid', isbn13: valid }
  return /^\d{13}$/.test(s) || /^\d{9}[\dX]$/.test(s) ? { kind: 'invalid' } : { kind: 'none' }
}

const TIMEOUT_MS = 12_000
/** A call to the `lookup` function: its own 8 s upstream limit plus a cold start, then a dead connection ends here. */
const FUNCTION_TIMEOUT_MS = 15_000

/** No source had the book, and at least one could not be asked: not the same as a clean miss. */
export class NotFoundWithErrors extends Error {
  constructor() {
    super("Not found, but a source could not be reached")
    this.name = "NotFoundWithErrors"
  }
}

function withTimeout<T>(p: Promise<T>, ms = TIMEOUT_MS): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('Timed out')), ms)
    p.then(
      (v) => {
        clearTimeout(t)
        resolve(v)
      },
      (e: unknown) => {
        clearTimeout(t)
        reject(e instanceof Error ? e : new Error(String(e)))
      },
    )
  })
}

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null)
const whole = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.round(v) : null)
/** Only web links are kept as covers. */
const cover = (v: unknown): string | null => {
  const t = text(v)
  return t !== null && /^https?:/i.test(t) ? t : null
}
const names = (v: unknown): string[] => (Array.isArray(v) ? v.map(text).filter((a): a is string => a !== null) : [])

/** A book from the function's JSON; a bad ISBN (Google may send "") becomes null. */
function fromFunction(o: Record<string, unknown>): Found | null {
  const title = text(o.title)
  if (!title) return null
  return {
    isbn: typeof o.isbn === 'string' ? normalizeIsbn(o.isbn) : null,
    title,
    authors: names(o.authors),
    publisher: text(o.publisher),
    year: whole(o.year),
    pages: whole(o.pages),
    coverUrl: cover(o.coverUrl),
    description: text(o.description),
  }
}

type Invoke = (path: string) => Promise<{ data: unknown; error: unknown }>

/** The function through supabase-js (GET, with the session's token). */
export function createLookupApi(invoke?: Invoke, timeoutMs = FUNCTION_TIMEOUT_MS): LookupApi {
  const call: Invoke =
    invoke ?? ((path) => supabase.functions.invoke(path, { method: 'GET' }) as Promise<{ data: unknown; error: unknown }>)
  const get = async (query: string): Promise<Record<string, unknown>> => {
    const { data, error } = await withTimeout(call(`lookup?${query}`), timeoutMs)
    if (error) throw error instanceof Error ? error : new Error('The lookup service failed')
    return (data ?? {}) as Record<string, unknown>
  }
  return {
    async bnp(isbn) {
      const r = await get(`source=bnp&isbn=${encodeURIComponent(isbn)}`)
      return r.found === true && r.book ? fromFunction(r.book as Record<string, unknown>) : null
    },
    async google(q) {
      const r = await get(`source=google&q=${encodeURIComponent(q)}`)
      const list = Array.isArray(r.results) ? (r.results as Record<string, unknown>[]) : []
      return list.map(fromFunction).filter((b): b is Found => b !== null)
    },
  }
}

type Fetch = (url: string) => Promise<Response>

const OL = 'https://openlibrary.org'

function yearFrom(s: unknown): number | null {
  const m = typeof s === 'string' ? /\b(\d{4})\b/.exec(s) : null
  return m ? Number(m[1]) : null
}

/** Open Library by ISBN; the authors are asked one by one and a failed one is left out, as in the app. */
/** One request and its JSON body, both under the timeout. */
async function getJson(fetchIt: Fetch, url: string, ms: number): Promise<{ status: number; ok: boolean; body: unknown }> {
  return withTimeout(
    (async () => {
      const res = await fetchIt(url)
      return { status: res.status, ok: res.ok, body: res.ok ? ((await res.json()) as unknown) : null }
    })(),
    ms,
  )
}

async function openLibraryIsbn(fetchIt: Fetch, isbn: string, ms: number): Promise<Found | null> {
  const get = (path: string) => getJson(fetchIt, `${OL}${path}`, ms)
  const res = await get(`/isbn/${isbn}.json`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Open Library answered ${String(res.status)}`)
  const ed = res.body as Record<string, unknown>
  const title = text(ed.title)
  if (!title) return null
  const refs = (Array.isArray(ed.authors) ? ed.authors : []) as { key?: unknown }[]
  const authorNames = await Promise.all(
    refs.map(async (r) => {
      if (typeof r.key !== 'string') return null
      try {
        const a = await get(`${r.key}.json`)
        return a.ok ? text((a.body as Record<string, unknown>).name) : null
      } catch {
        return null
      }
    }),
  )
  const cover = (Array.isArray(ed.covers) ? (ed.covers as unknown[]) : []).find((c) => typeof c === 'number' && c > 0)
  const publishers = Array.isArray(ed.publishers) ? (ed.publishers as unknown[]) : []
  return {
    isbn,
    title,
    authors: authorNames.filter((n): n is string => n !== null),
    publisher: text(publishers[0]),
    year: yearFrom(ed.publish_date),
    pages: whole(ed.number_of_pages),
    coverUrl: cover !== undefined ? `https://covers.openlibrary.org/b/id/${String(cover)}-L.jpg` : null,
    description: null,
  }
}

async function openLibrarySearch(fetchIt: Fetch, q: string, ms: number): Promise<Found[]> {
  const fields = 'title,author_name,publisher,first_publish_year,number_of_pages_median,isbn,cover_i'
  const res = await getJson(fetchIt, `${OL}/search.json?q=${encodeURIComponent(q)}&limit=10&fields=${fields}`, ms)
  if (!res.ok) throw new Error(`Open Library answered ${String(res.status)}`)
  const docs = (res.body as { docs?: Record<string, unknown>[] }).docs ?? []
  const out: Found[] = []
  for (const d of docs) {
    const title = text(d.title)
    if (!title) continue
    const isbns = Array.isArray(d.isbn) ? (d.isbn as unknown[]) : []
    const isbn = isbns.map((i) => (typeof i === 'string' ? normalizeIsbn(i) : null)).find((i) => i !== null) ?? null
    const publishers = Array.isArray(d.publisher) ? (d.publisher as unknown[]) : []
    const cover = whole(d.cover_i)
    out.push({
      isbn,
      title,
      authors: names(d.author_name),
      publisher: text(publishers[0]),
      year: whole(d.first_publish_year),
      pages: whole(d.number_of_pages_median),
      coverUrl: cover !== null ? `https://covers.openlibrary.org/b/id/${String(cover)}-L.jpg` : null,
      description: null,
    })
  }
  return out
}

/**
 * The lookup chain, in the app's order: BNP, Open Library, Google Books. A source that fails (an error, a timeout) is
 * skipped; only when no source could answer at all does the lookup throw.
 */
export function createLookup(api: LookupApi, fetchIt: Fetch = (u) => fetch(u), timeoutMs = TIMEOUT_MS): Lookup {
  return {
    async lookupIsbn(isbn) {
      const sources: (() => Promise<Found | null>)[] = [
        () => api.bnp(isbn),
        () => openLibraryIsbn(fetchIt, isbn, timeoutMs),
        async () => (await api.google(`isbn:${isbn}`))[0] ?? null,
      ]
      let failed = 0
      let missed = 0
      for (const [i, source] of sources.entries()) {
        try {
          const found = await source()
          if (!found) {
            missed += 1
            continue
          }
          const hit = { ...found, isbn: found.isbn ?? isbn }
          if (hit.coverUrl !== null) return hit
          // As the app's LookupChain: a hit without a cover asks the later sources for a cover only.
          for (const later of sources.slice(i + 1)) {
            try {
              const c = (await later())?.coverUrl
              if (c) return { ...hit, coverUrl: c }
            } catch {
              // A later source's trouble does not matter now.
            }
          }
          return hit
        } catch {
          failed += 1
        }
      }
      if (missed === 0) throw new Error('Could not look up that ISBN')
      if (failed > 0) throw new NotFoundWithErrors()
      return null
    },
    async searchTitle(q) {
      const [google, ol] = await Promise.allSettled([api.google(q), openLibrarySearch(fetchIt, q, timeoutMs)])
      if (google.status === 'rejected' && ol.status === 'rejected') throw new Error('Search failed')
      const all = [...(google.status === 'fulfilled' ? google.value : []), ...(ol.status === 'fulfilled' ? ol.value : [])]
      // Nothing found while a source was down is not a clean "no results".
      if (all.length === 0 && (google.status === 'rejected' || ol.status === 'rejected')) throw new NotFoundWithErrors()
      const seen = new Set<string>()
      return all.filter((b) => {
        const key = b.isbn ?? `${b.title.toLowerCase()}|${b.authors[0]?.toLowerCase() ?? ''}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
    },
  }
}

/** The real chain. */
export const defaultLookup: Lookup = createLookup(createLookupApi())
