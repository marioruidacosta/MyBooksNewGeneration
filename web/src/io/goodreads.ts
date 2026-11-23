import type { Book, Status } from '../sync/model'

/** As the app's `GoodreadsFormatException`: the file isn't a Goodreads library export. */
export class GoodreadsFormatException extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'GoodreadsFormatException'
  }
}

/** Longest review kept (the app's `MAX_REVIEW_LENGTH`) and tag (`MAX_TAG_LENGTH`). */
export const MAX_REVIEW_LENGTH = 2000
const MAX_TAG_LENGTH = 40

/** Goodreads' extra shelf for "To buy" (it has no such status). */
const TO_BUY_SHELF = 'to-buy'
const STATUS_SHELVES = new Set(['read', 'currently-reading', 'to-read', TO_BUY_SHELF])
const COLUMNS = [
  'Title', 'Author', 'Additional Authors', 'ISBN', 'ISBN13', 'My Rating', 'Publisher', 'Number of Pages',
  'Year Published', 'Date Read', 'Date Added', 'Bookshelves', 'Exclusive Shelf', 'My Review',
]

// ---- CSV (RFC 4180), as the app's `Csv` ----

/** The rows of [text]; a leading byte-order mark and blank lines are ignored, CRLF and LF both end a row. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  let i = text.startsWith('﻿') ? 1 : 0
  const endRow = () => {
    row.push(field)
    field = ''
    if (row.length > 1 || row[0] !== '') rows.push(row)
    row = []
  }
  while (i < text.length) {
    const c = text[i]
    if (quoted && c === '"' && text[i + 1] === '"') {
      field += '"'
      i++
    } else if (quoted && c === '"') quoted = false
    else if (quoted && c === '\r' && text[i + 1] === '\n') {
      // CRLF inside a field is one line break.
    } else if (quoted) field += c
    else if (c === '"') quoted = true
    else if (c === ',') {
      row.push(field)
      field = ''
    } else if (c === '\r' && text[i + 1] === '\n') {
      // The \n ends the row.
    } else if (c === '\n' || c === '\r') endRow()
    else field += c
    i++
  }
  if (field !== '' || row.length > 0) endRow()
  return rows
}

/** [rows] as CSV with CRLF line ends; fields with commas, quotes or line breaks are quoted. */
export function writeCsv(rows: string[][]): string {
  return rows
    .map((r) => r.map((f) => (/[,"\n\r]/.test(f) ? `"${f.replace(/"/g, '""')}"` : f)).join(',') + '\r\n')
    .join('')
}

// ---- text rules shared with the app (`domain/Collections.kt`) ----

/** Tags with spaces squeezed, cut to the limit, and each name once (ignoring capitals). */
export function cleanTags(tags: string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const tag of tags) {
    const t = tag.replace(/\s+/g, ' ').trim().slice(0, MAX_TAG_LENGTH).trim()
    if (t !== '' && !seen.has(t.toLowerCase())) {
      seen.add(t.toLowerCase())
      out.push(t)
    }
  }
  return out
}

// "(Series, #1)", "(Series #2.5)", "(Series, #1-3)" at the end of a title, as Goodreads writes it.
const SERIES_IN_TITLE =
  /^(.*\S)\s*\(\s*([^()]*?[^\s,()])\s*,?\s*#\s*([0-9]+(?:\.[0-9]+)?(?:\s*[-–]\s*[0-9]+(?:\.[0-9]+)?)?)\s*\)\s*$/

/** "The Hobbit (Middle-earth, #0.5)" as "The Hobbit", "Middle-earth", "0.5"; null when the title has no series. */
export function splitSeries(title: string): { title: string; series: string; number: string } | null {
  const m = SERIES_IN_TITLE.exec(title.trim())
  if (!m) return null
  return { title: m[1].trim(), series: m[2].trim(), number: m[3].replace(/\s/g, '').replace('–', '-') }
}

/** [title] with its series the Goodreads way. */
export function withSeries(title: string, series: string | null, number: string | null): string {
  if (!series || series.trim() === '') return title
  if (!number || number.trim() === '') return `${title} (${series})`
  return `${title} (${series}, #${number})`
}

/** ISBN-10 or ISBN-13 (hyphens and spaces allowed) as 13 digits, or null (the app's `Isbn.normalize`). */
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

// ---- Goodreads ----

/** Goodreads writes ISBNs as ="9780441013593" so spreadsheets keep them as text. */
const unquoteIsbn = (value: string) => value.replace(/^=/, '').replace(/^"+|"+$/g, '')

/** "2024/03/15" (Goodreads' format), as the start of that day here; null for anything else, a day that isn't one too. */
function readDate(value: string): number | null {
  const parts = value.split(/[/-]/).map((p) => (/^\d+$/.test(p.trim()) ? Number(p) : NaN))
  if (parts.length !== 3 || parts.some(Number.isNaN)) return null
  const [y, m, d] = parts
  const date = new Date(y, m - 1, d)
  if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d) return null
  return date.getTime()
}

const pad = (n: number) => String(n).padStart(2, '0')

function writeDate(ms: number): string {
  const d = new Date(ms)
  return `${String(d.getFullYear())}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}

/** Goodreads shelf names have no commas or spaces: "Gift from Ana" goes as "gift-from-ana". */
const shelfName = (tag: string) => tag.toLowerCase().replace(/[,\s]+/g, '-')

/** Goodreads reviews are HTML: line breaks kept, other tags dropped, entities decoded. */
const plainText = (markup: string) =>
  markup.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]*>/g, '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .trim()

const toMarkup = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br/>')

/** Rounds like Kotlin's `roundToInt`: ties go up. */
const wholeStars = (text: string): number | null => {
  const n = text.trim() === '' ? NaN : Number(text)
  const r = Math.round(n)
  return Number.isFinite(r) && r >= 1 && r <= 5 ? r : null
}

const wholeNumber = (text: string): number | null => (/^[+-]?\d+$/.test(text.trim()) ? Number(text) : null)

/**
 * Goodreads' library CSV (My Books, Import and export), as books with new ids. A row without a title gives an
 * untitled book, for the importer to skip. Dates are calendar days here. Throws [GoodreadsFormatException] when the
 * file isn't a Goodreads export.
 */
export function parseGoodreads(csv: string): Book[] {
  const rows = parseCsv(csv)
  const header = (rows[0] ?? []).map((h) => h.trim())
  if (!header.includes('Title') || !header.includes('Author') || !header.includes('Exclusive Shelf')) {
    throw new GoodreadsFormatException('Not a Goodreads library export')
  }
  return rows.slice(1).map((cells) => {
    const row = (name: string): string => {
      const i = header.indexOf(name)
      return i >= 0 ? (cells[i] ?? '').trim() : ''
    }
    const shelves = row('Bookshelves').split(',').map((s) => s.trim()).filter((s) => s !== '')
    const series = splitSeries(row('Title'))
    const pages = wholeNumber(row('Number of Pages'))
    const exclusive = row('Exclusive Shelf')
    const status: Status =
      exclusive === 'read' ? 'READ' : exclusive === 'currently-reading' ? 'READING' : shelves.includes(TO_BUY_SHELF) ? 'WISHLIST' : 'TO_READ'
    return {
      id: crypto.randomUUID(),
      isbn: normalizeIsbn(unquoteIsbn(row('ISBN13'))) ?? normalizeIsbn(unquoteIsbn(row('ISBN'))),
      title: series?.title ?? row('Title'),
      authors: [row('Author'), ...row('Additional Authors').split(',')].map((a) => a.trim()).filter((a) => a !== '').join(', '),
      publisher: row('Publisher') || null,
      publishedYear: wholeNumber(row('Year Published') || row('Original Publication Year')),
      pageCount: pages !== null && pages > 0 ? pages : null,
      coverUrl: null,
      description: null,
      status,
      // Goodreads writes "5.0" and, for half and quarter stars, "4.5" or "4.25": rounded to whole stars.
      rating: wholeStars(row('My Rating')),
      notes: row('Private Notes'),
      dateAdded: readDate(row('Date Added')) ?? 0,
      dateRead: readDate(row('Date Read')),
      dateStarted: null,
      currentPage: null,
      progressPercent: null,
      review: plainText(row('My Review')).slice(0, MAX_REVIEW_LENGTH),
      // Shelves other than the reading ones (and to-buy, which is a status here) are tags.
      tags: cleanTags(shelves.filter((s) => !STATUS_SHELVES.has(s.toLowerCase()))),
      series: series?.series ?? null,
      seriesNumber: series?.number ?? null,
      lentTo: null,
      lentOn: null, shelfId: null, shelfPosition: null,
      deleted: false,
    }
  })
}

/** The books in Goodreads' format (private notes are left out). */
export function writeGoodreads(books: Book[]): string {
  return writeCsv([
    COLUMNS,
    ...books.map((b) => {
      const authors = b.authors.split(',').map((a) => a.trim()).filter((a) => a !== '')
      return [
        withSeries(b.title, b.series, b.seriesNumber),
        authors[0] ?? '',
        authors.slice(1).join(', '),
        '',
        b.isbn ?? '',
        String(b.rating ?? 0),
        b.publisher ?? '',
        b.pageCount === null ? '' : String(b.pageCount),
        b.publishedYear === null ? '' : String(b.publishedYear),
        b.dateRead !== null && b.status === 'READ' ? writeDate(b.dateRead) : '',
        b.dateAdded > 0 ? writeDate(b.dateAdded) : '',
        [...b.tags.map(shelfName), ...(b.status === 'WISHLIST' ? [TO_BUY_SHELF] : [])].join(', '),
        b.status === 'READ' ? 'read' : b.status === 'READING' ? 'currently-reading' : 'to-read',
        toMarkup(b.review),
      ]
    }),
  ])
}
