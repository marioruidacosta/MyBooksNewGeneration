import type { NewsLang } from './parse'

export interface Feed {
  id: string
  name: string
  lang: NewsLang
  url: string
  /** Keeps only the stories that return true. No filter keeps everything. */
  filter?: (title: string, summary: string) => boolean
}

const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

// "prémio" (a prize) only counts together with one of these, so it needs no entry of its own.
const BOOK_WORDS = ['livro', 'livros', 'escritor', 'escritora', 'poeta', 'poesia', 'romance', 'literatura', 'literario', 'literaria', 'editora']
const BOOK_PATTERN = new RegExp(`(?<![a-z])(?:${BOOK_WORDS.join('|')})(?![a-z])`)

/** RTP Cultura, Expresso, Observador and SAPO Mag cover far more than books: keep only the stories about books, ignoring case and accents. */
export function keepsBookStory(title: string, summary: string): boolean {
  return BOOK_PATTERN.test(fold(`${title} ${summary}`))
}

export const FEEDS: Feed[] = [
  { id: 'apel', name: 'APEL', lang: 'pt', url: 'https://www.apel.pt/rss' },
  { id: 'publico', name: 'Público', lang: 'pt', url: 'https://www.publico.pt/api/list/rss/livros' },
  { id: 'expresso', name: 'Expresso', lang: 'pt', url: 'https://feeds.feedburner.com/expresso-geral', filter: keepsBookStory },
  { id: 'observador', name: 'Observador', lang: 'pt', url: 'https://observador.pt/feed/', filter: keepsBookStory },
  { id: 'sapomag', name: 'SAPO Mag', lang: 'pt', url: 'https://mag.sapo.pt/rss', filter: keepsBookStory },
  { id: 'jl', name: 'Jornal de Letras', lang: 'pt', url: 'https://jornaldeletras.pt/feed/' },
  { id: 'rtp', name: 'RTP Cultura', lang: 'pt', url: 'https://www.rtp.pt/noticias/rss/cultura', filter: keepsBookStory },
  { id: 'guardian', name: 'The Guardian Books', lang: 'en', url: 'https://www.theguardian.com/books/rss' },
  { id: 'lithub', name: 'Literary Hub', lang: 'en', url: 'https://lithub.com/feed/' },
  { id: 'nyt', name: 'The New York Times Books', lang: 'en', url: 'https://rss.nytimes.com/services/xml/rss/nyt/Books.xml' },
  { id: 'npr', name: 'NPR Books', lang: 'en', url: 'https://www.npr.org/rss/rss.php?id=1032' },
]
