import { authorKey, catalogTitleKey, firstAuthorKey, searchKey } from '../text/keys'

/** What Wikidata knows about a book's work: shown under the book's own details. */
export type BookFacts = {
  /** The year the work first came out ("1995"). */
  firstPublished: string | null
  /** Its title in the language it was written in, and that language ("português"). */
  originalTitle: string | null
  originalLanguage: string | null
  genres: string[]
  awards: string[]
  /** The works before and after it ("Seeing"). */
  follows: string | null
  followedBy: string | null
  /** The Wikidata page, credited under the facts. */
  pageUrl: string
}

const API = 'https://www.wikidata.org/w/api.php'
/** Labels in Portuguese first (most of the library is Portuguese editions), then English. */
const LABEL_LANGUAGES = ['pt', 'en']
/** The work's own languages, for its title as first written (Wikidata's "title" field is often wrong). */
const WRITTEN_IN: Record<string, string> = {
  Q5146: 'pt', Q1860: 'en', Q1321: 'es', Q150: 'fr', Q188: 'de', Q652: 'it', Q7737: 'ru', Q5287: 'ja', Q809: 'pl',
  Q7411: 'nl', Q9027: 'sv', Q9035: 'da', Q9043: 'no', Q1412: 'fi', Q9056: 'cs', Q9176: 'ko', Q7850: 'zh', Q13955: 'ar',
  Q256: 'tr', Q9288: 'he', Q9067: 'hu', Q9063: 'sl', Q7913: 'ro', Q9129: 'el', Q8798: 'uk', Q6654: 'hr', Q8748: 'ca',
}

type Snak = { mainsnak?: { datavalue?: { value?: unknown } } }
type Entity = { id: string; labels?: Record<string, { value: string }>; claims?: Record<string, Snak[]> }

/** The item ids a property points to. */
function refs(e: Entity, property: string): string[] {
  return (e.claims?.[property] ?? [])
    .map((s) => (s.mainsnak?.datavalue?.value as { id?: unknown } | undefined)?.id)
    .filter((id): id is string => typeof id === 'string')
}

function label(e: Entity | undefined, languages = LABEL_LANGUAGES): string | null {
  for (const lang of languages) {
    const v = e?.labels?.[lang]?.value
    if (v) return v
  }
  return null
}

/** "1995" from Wikidata's "+1995-00-00T00:00:00Z"; years before the common era as "BC". */
function year(e: Entity): string | null {
  const time = (e.claims?.P577?.[0]?.mainsnak?.datavalue?.value as { time?: unknown } | undefined)?.time
  const m = typeof time === 'string' ? /^([+-])0*(\d+)-/.exec(time) : null
  if (!m) return null
  return m[1] === '-' ? `${m[2]} BC` : m[2]
}

async function get(params: Record<string, string>, fetchImpl: typeof fetch): Promise<Record<string, unknown>> {
  const url = `${API}?${new URLSearchParams({ ...params, format: 'json', origin: '*' }).toString()}`
  const res = await fetchImpl(url)
  if (!res.ok) throw new Error(`Wikidata ${String(res.status)}`)
  return (await res.json()) as Record<string, unknown>
}

async function entities(ids: string[], props: string, languages: string, fetchImpl: typeof fetch): Promise<Record<string, Entity>> {
  if (ids.length === 0) return {}
  const out: Record<string, Entity> = {}
  // At most 50 ids per request.
  for (let i = 0; i < ids.length; i += 50) {
    const r = await get({ action: 'wbgetentities', ids: ids.slice(i, i + 50).join('|'), props, languages }, fetchImpl)
    Object.assign(out, (r.entities ?? {}) as Record<string, Entity>)
  }
  return out
}

/** The work among the items called [title] that was written by the person [author] (an authorKey). */
async function findWork(title: string, author: string, fetchImpl: typeof fetch): Promise<Entity | null> {
  const languages = ['pt', ...LABEL_LANGUAGES.filter((l) => l !== 'pt'), ...Object.values(WRITTEN_IN)].filter((l, i, a) => a.indexOf(l) === i)
  for (const lang of LABEL_LANGUAGES) {
    const found = await get({ action: 'wbsearchentities', search: title, language: lang, uselang: lang, type: 'item', limit: '7' }, fetchImpl)
    const ids = ((found.search ?? []) as { id?: unknown }[]).map((s) => s.id).filter((id): id is string => typeof id === 'string')
    if (ids.length === 0) continue
    const items = await entities(ids, 'claims|labels', languages.join('|'), fetchImpl)
    const writers = await entities([...new Set(ids.flatMap((id) => (items[id] ? refs(items[id], 'P50') : [])))], 'labels', 'pt|en', fetchImpl)
    const work = ids
      .map((id) => items[id])
      .find((e) => e && refs(e, 'P50').some((w) => LABEL_LANGUAGES.some((l) => authorKey(writers[w]?.labels?.[l]?.value ?? '') === author)))
    if (work) return work
  }
  return null
}

/** Answers, found or not, kept for the session. */
const known = new Map<string, BookFacts | null>()

/** What Wikidata knows about the work behind a book, matched by title and first author; null when nothing matches. */
export async function bookFacts(title: string, authors: string, fetchImpl: typeof fetch = (url, init) => fetch(url, init)): Promise<BookFacts | null> {
  const author = firstAuthorKey(authors)
  const key = `${searchKey(title.trim())}|${author}`
  if (title.trim() === '' || author === '') return null
  if (known.has(key)) return known.get(key) ?? null
  let facts: BookFacts | null = null
  try {
    const work = await findWork(title.trim(), author, fetchImpl)
    if (work) {
      const genres = refs(work, 'P136').slice(0, 3)
      const language = refs(work, 'P407')[0]
      const names = await entities(
        [...new Set([...refs(work, 'P166'), ...genres, ...refs(work, 'P155').slice(0, 1), ...refs(work, 'P156').slice(0, 1), ...(language ? [language] : [])])],
        'labels', 'pt|en', fetchImpl,
      )
      const named = (ids: string[]) => ids.map((id) => label(names[id])).filter((v): v is string => v !== null)
      const writtenIn = language ? WRITTEN_IN[language] : undefined
      facts = {
        firstPublished: year(work),
        originalTitle: writtenIn ? label(work, [writtenIn]) : null,
        originalLanguage: language ? label(names[language]) : null,
        genres: named(genres),
        awards: named(refs(work, 'P166')),
        follows: named(refs(work, 'P155').slice(0, 1))[0] ?? null,
        followedBy: named(refs(work, 'P156').slice(0, 1))[0] ?? null,
        pageUrl: `https://www.wikidata.org/wiki/${work.id}`,
      }
    }
  } catch {
    facts = null
  }
  known.set(key, facts)
  return facts
}

/** The details panel's extra lines; the original title only when the book isn't already it. */
export function factRows(facts: BookFacts, bookTitle: string): [string, string][] {
  const rows: [string, string | null][] = [
    ['First published', facts.firstPublished],
    [
      'Original title',
      facts.originalTitle && catalogTitleKey(facts.originalTitle) !== catalogTitleKey(bookTitle)
        ? facts.originalLanguage ? `${facts.originalTitle} (${facts.originalLanguage})` : facts.originalTitle
        : null,
    ],
    ['Genre', facts.genres.length > 0 ? facts.genres.slice(0, 3).join(', ') : null],
    ['Awards', facts.awards.length > 0 ? facts.awards.join(', ') : null],
    ['Follows', facts.follows],
    ['Followed by', facts.followedBy],
  ]
  return rows.filter((r): r is [string, string] => r[1] !== null)
}
