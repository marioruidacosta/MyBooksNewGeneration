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
const BOOK_WORDS = [
  'livro', 'livros', 'escritor', 'escritora', 'escritores', 'escritoras', 'poeta', 'poetas', 'poesia', 'literatura', 'literario', 'literaria',
  'editora', 'editoras', 'editores', 'autores', 'autoras', 'leitores', 'leitura',
]
// "romance" is left out on purpose: it is also gossip ("romance between two actors"), so it never counts on its own.
const wordPattern = (words: string[]) => new RegExp(`(?<![a-z])(?:${words.join('|')})(?![a-z])`)
const BOOK_PATTERN = wordPattern(BOOK_WORDS)
// Phrases that contain a book word but are not about books. Tested on the folded text.
const NOT_BOOKS = [
  'livro de reclama', 'livro branco', 'livro verde', 'livro negro', 'livro de recordes', 'livro de ponto', 'livro de cheques',
  'livro de registo', 'editora de moda', 'editora-chefe',
]

/** RTP Cultura, Expresso, Observador and SAPO Mag cover far more than books: keep only the stories about books, ignoring case and accents. */
export function keepsBookStory(title: string, summary: string): boolean {
  let text = fold(`${title} ${summary}`)
  for (const phrase of NOT_BOOKS) text = text.split(phrase).join(' ')
  return BOOK_PATTERN.test(text)
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
