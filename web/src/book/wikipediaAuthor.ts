import { searchKey } from '../text/keys'

/** An author as Wikipedia describes them, as the app's WikipediaAuthors finds them. */
export type AuthorInfo = {
  name: string
  /** "escritor português, Nobel da Literatura de 1998". */
  description: string | null
  extract: string
  photoUrl: string | null
  /** The Wikipedia page, credited under the text. */
  pageUrl: string | null
}

/** Portuguese first (most of the library is Portuguese editions), then English. */
const LANGUAGES = ['pt', 'en']

/** Word stems (accent-free, lower case) of the people whose books end up on a shelf. */
const WRITER_WORDS = [
  'escritor', 'autor', 'poet', 'romancista', 'novelista', 'ensaista', 'dramaturg', 'jornalista', 'contista',
  'cronista', 'filosof', 'historiador', 'tradutor', 'ilustrador', 'biografo', 'argumentista', 'cartoonista',
  'writer', 'author', 'novelist', 'essayist', 'playwright', 'journalist', 'philosopher', 'historian',
  'translator', 'illustrator', 'biographer', 'screenwriter', 'cartoonist',
]

type Summary = {
  type?: string
  title?: string
  description?: string
  extract?: string
  thumbnail?: { source?: string }
  content_urls?: { mobile?: { page?: string } }
}

/** Answers, found or not, kept for the session. */
const known = new Map<string, AuthorInfo | null>()

async function summary(lang: string, name: string, fetchImpl: typeof fetch): Promise<AuthorInfo | null> {
  try {
    const title = encodeURIComponent(name.replace(/ /g, '_'))
    const res = await fetchImpl(`https://${lang}.wikipedia.org/api/rest_v1/page/summary/${title}`)
    if (!res.ok) return null
    const s = (await res.json()) as Summary
    const extract = s.extract ?? ''
    if (s.type !== 'standard' || extract.trim() === '') return null
    // A page counts only when it describes a writer, so a footballer with the same name isn't shown.
    const text = searchKey(`${s.description ?? ''} ${extract.split('. ')[0]}`)
    if (!WRITER_WORDS.some((w) => text.includes(w))) return null
    return {
      name: s.title || name,
      description: s.description ?? null,
      extract,
      photoUrl: s.thumbnail?.source ?? null,
      pageUrl: s.content_urls?.mobile?.page ?? null,
    }
  } catch {
    return null
  }
}

/** An author's short biography from Wikipedia; null when none is found (or there's no connection). */
export async function authorInfo(name: string, fetchImpl: typeof fetch = (url, init) => fetch(url, init)): Promise<AuthorInfo | null> {
  const trimmed = name.trim()
  const key = searchKey(trimmed)
  if (key === '') return null
  if (known.has(key)) return known.get(key) ?? null
  let found: AuthorInfo | null = null
  for (const lang of LANGUAGES) {
    found = await summary(lang, trimmed, fetchImpl)
    if (found) break
  }
  known.set(key, found)
  return found
}
