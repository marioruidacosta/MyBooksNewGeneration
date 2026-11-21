import { createHash } from 'node:crypto'
import { XMLParser, XMLValidator } from 'fast-xml-parser'

export type NewsLang = 'pt' | 'en'

export interface NewsItem {
  id: string
  title: string
  summary: string
  url: string
  source: string
  lang: NewsLang
  published: number
  image: string | null
}

export interface ParseContext {
  source: string
  lang: NewsLang
  /** Fetch time: an item with no usable date counts as published now, and nothing is dated in the future. */
  now: number
}

const MAX_TITLE = 200
const MAX_SUMMARY = 280

const NAMED_ENTITIES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', hellip: '…', ndash: '–', mdash: '—',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', laquo: '«', raquo: '»',
}

function decodeEntities(s: string): string {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, body: string) => {
    if (body[0] === '#') {
      const code = body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10)
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : ' '
    }
    return NAMED_ENTITIES[body.toLowerCase()] ?? whole
  })
}

// Tags longer than this are not tags. The bound also keeps a hostile "<<<<<..." from costing quadratic time.
const MAX_TAG = 500
// Raw text is cut to this before any stripping, so no input can make the cleaning slow.
export const MAX_RAW = 20_000

function stripTags(s: string): string {
  return s
    .replace(/<(script|style)\b[\s\S]{0,20000}?<\/\1\s*>/gi, ' ')
    .replace(/<!--[\s\S]{0,20000}?-->/g, ' ')
    .replace(new RegExp(`<\\/?(p|br|div|li|ul|ol|h[1-6]|blockquote|tr|table)\\b[^>]{0,${MAX_TAG}}>`, 'gi'), ' ')
    .replace(new RegExp(`<[^>]{0,${MAX_TAG}}>`, 'g'), '')
}

// Control characters, bidirectional overrides and isolates, zero-width marks, and lone surrogates.
const INVISIBLE = new RegExp(
  '[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]' +
    '|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]',
  'g',
)

/** Plain text from an HTML fragment: tags and scripts gone, entities decoded, whitespace collapsed. */
export function toPlainText(html: string): string {
  // A second pass catches markup that only appears once the entities are decoded.
  return stripTags(decodeEntities(stripTags(html.slice(0, MAX_RAW))))
    .replace(INVISIBLE, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function cleanTitle(raw: string): string {
  return cap(toPlainText(raw), MAX_TITLE)
}

function cap(text: string, max: number): string {
  if (text.length <= max) return text
  const room = text.slice(0, max - 1)
  const space = room.lastIndexOf(' ')
  const cut = space > max * 0.5 ? room.slice(0, space) : room
  return `${cut.replace(/[\s,;:.\-–—]+$/, '')}…`
}

/**
 * Plain-text summary. It can be the empty string: some feeds (APEL) only carry a footer, which is dropped, so
 * everything that shows a summary must cope with "".
 */
export function summarize(html: string): string {
  const plain = toPlainText(html).replace(/\s*The post .{0,300}? appeared first on .{0,100}?\.?$/, '')
  return cap(plain, MAX_SUMMARY)
}

export function normalizeTitle(title: string): string {
  return title.toLowerCase().replace(/\s+/g, ' ').trim()
}

/** The URL without its query or hash, for spotting the same story twice. */
export function normalizeUrl(url: string): string {
  try {
    const u = new URL(url)
    return `${u.protocol}//${u.host.toLowerCase()}${u.pathname.replace(/\/+$/, '')}`
  } catch {
    return url
  }
}

export function httpsUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  try {
    const u = new URL(value.trim())
    return u.protocol === 'https:' && !u.username && !u.password ? u.toString() : null
  } catch {
    return null
  }
}

type Node = Record<string, unknown>
const asArray = (v: unknown): unknown[] => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v])
const isNode = (v: unknown): v is Node => typeof v === 'object' && v !== null

function text(v: unknown): string {
  if (typeof v === 'string') return v
  if (typeof v === 'number') return String(v)
  if (Array.isArray(v)) return text(v[0])
  if (isNode(v)) return text(v['#text'])
  return ''
}

function attr(v: unknown, name: string): string {
  return isNode(v) && typeof v[`@_${name}`] === 'string' ? (v[`@_${name}`] as string) : ''
}

function linkOf(entry: Node): string | null {
  const links = asArray(entry.link)
  // Atom: prefer rel="alternate" (or no rel); RSS: the element text.
  const candidates: string[] = []
  for (const l of links) {
    const rel = attr(l, 'rel')
    if (isNode(l) && attr(l, 'href')) {
      if (rel === '' || rel === 'alternate') candidates.unshift(attr(l, 'href'))
    } else if (text(l)) candidates.push(text(l))
  }
  const guid = isNode(entry.guid) ? text(entry.guid) : text(entry.guid)
  if (guid) candidates.push(guid)
  if (text(entry.id)) candidates.push(text(entry.id))
  return httpsUrl(candidates[0]) ?? null
}

/** Smallest width a story picture may declare and still be worth showing, and the widest we will download. */
const MIN_IMAGE_WIDTH = 150
const MAX_IMAGE_WIDTH = 1200
const NPR_WIDTH = 400

/** Tracking pixels, spacers and beacons are never pictures. Judged by the path, so a query string can't trigger it. */
function isTrackingUrl(url: string): boolean {
  try {
    return /\/tracking\/|pixel|spacer|beacon|1x1/i.test(new URL(url).pathname)
  } catch {
    return true
  }
}

/**
 * NPR serves its pictures through brightspot, which resizes on request: ".../resize/4356x2904!/..." is the full
 * original, ".../resize/400/..." a 400 px wide one. Returns the smaller URL, or null when it isn't such a URL.
 */
function resizedNprUrl(url: string): string | null {
  try {
    const u = new URL(url)
    if (!u.hostname.endsWith('.brightspotcdn.com') || !/\/resize\/\d+x\d+!\//.test(u.pathname)) return null
    u.pathname = u.pathname.replace(/\/resize\/\d+x\d+!\//, `/resize/${String(NPR_WIDTH)}/`)
    return u.toString()
  } catch {
    return null
  }
}

/**
 * The New York Times feed offers one 1800 px picture per story, but its image server keeps other renditions under the
 * same name: "-mediumThreeByTwo440" is 440 px wide, about 30 KB instead of 500. Null when it isn't such a URL.
 */
function smallerNytUrl(url: string): string | null {
  try {
    const u = new URL(url)
    if (u.hostname !== 'static01.nyt.com' || !u.pathname.includes('-mediumSquareAt3X.')) return null
    u.pathname = u.pathname.replace('-mediumSquareAt3X.', '-mediumThreeByTwo440.')
    return u.toString()
  } catch {
    return null
  }
}

interface Candidate {
  url: string
  /** The width the feed declares, if any. */
  width: number | null
}

const declaredWidth = (v: unknown): number | null => {
  const n = parseInt(attr(v, 'width'), 10)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** Of the pictures one feed element offers: the smallest that is wide enough; with no widths at all, the first. */
function bestOf(list: Candidate[]): Candidate | null {
  const known = list.filter((c) => c.width !== null)
  if (known.length === 0) return list[0] ?? null
  return known.filter((c) => (c.width ?? 0) >= MIN_IMAGE_WIDTH).sort((a, b) => (a.width ?? 0) - (b.width ?? 0))[0] ?? null
}

/** The picture URL when it is fit to show (https, not a tracker, not oversized), possibly resized; null otherwise. */
function usable(c: Candidate): string | null {
  const ok = httpsUrl(c.url)
  if (!ok || isTrackingUrl(ok)) return null
  const small = resizedNprUrl(ok) ?? smallerNytUrl(ok)
  if (small) return small
  return c.width !== null && c.width > MAX_IMAGE_WIDTH ? null : ok
}

/** A picture URL (from a file we wrote earlier) made fit to show: https, not a tracker, resized when it is NPR's; else null. */
export function cleanImage(value: unknown): string | null {
  return typeof value === 'string' ? usable({ url: value, width: null }) : null
}

function bodyImages(bodyHtml: string): Candidate[] {
  const found: Candidate[] = []
  const re = /<img\b([^>]{0,2000})>/gi
  const html = bodyHtml.slice(0, 100_000)
  for (let m = re.exec(html); m && found.length < 20; m = re.exec(html)) {
    const tag = m[1]
    const src = /\bsrc\s*=\s*["']([^"']{1,2000})["']/i.exec(tag)
    if (!src) continue
    const size = (name: string) => {
      const v = new RegExp(`\\b${name}\\s*=\\s*["']?(\\d+)`, 'i').exec(tag)
      return v ? parseInt(v[1], 10) : null
    }
    const w = size('width')
    const h = size('height')
    // 1 or 2 pixels wide or high: a tracker, whatever its URL says.
    if ((w !== null && w <= 2) || (h !== null && h <= 2)) continue
    found.push({ url: decodeEntities(src[1]), width: w })
  }
  return found
}

function imageOf(entry: Node, bodyHtml: string): string | null {
  const media: Candidate[] = []
  for (const m of asArray(entry['media:content'])) {
    const type = attr(m, 'type')
    const medium = attr(m, 'medium')
    if ((!type && !medium) || type.startsWith('image/') || medium === 'image') media.push({ url: attr(m, 'url'), width: declaredWidth(m) })
  }
  for (const m of asArray(entry['media:thumbnail'])) media.push({ url: attr(m, 'url'), width: declaredWidth(m) })
  const pictures = media.filter((c) => httpsUrl(c.url) !== null && !isTrackingUrl(httpsUrl(c.url) ?? ''))
  const chosen = bestOf(pictures)
  const first = chosen ? usable(chosen) : null
  if (first) return first
  const rest: Candidate[] = []
  for (const e of asArray(entry.enclosure)) {
    if (attr(e, 'type').startsWith('image/')) rest.push({ url: attr(e, 'url'), width: null })
  }
  for (const l of asArray(entry.link)) {
    if (attr(l, 'rel') === 'enclosure' && attr(l, 'type').startsWith('image/')) rest.push({ url: attr(l, 'href'), width: null })
  }
  rest.push(...bodyImages(bodyHtml))
  for (const c of rest) {
    const ok = usable(c)
    if (ok) return ok
  }
  return null
}

function dateOf(entry: Node): number | null {
  for (const key of ['pubDate', 'published', 'updated', 'dc:date']) {
    const raw = text(entry[key]).trim()
    if (!raw) continue
    const t = Date.parse(raw)
    if (Number.isFinite(t)) return t
  }
  return null
}

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: '@_',
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: true,
  processEntities: true,
  isArray: (name) => ['item', 'entry', 'link', 'media:content', 'media:thumbnail', 'enclosure'].includes(name),
})

/** Parses an RSS 2.0 or Atom document. Throws when the text is not a feed (bad XML, an HTML error page). */
export function parseFeed(xml: string, ctx: ParseContext): NewsItem[] {
  const valid = XMLValidator.validate(xml)
  if (valid !== true) throw new Error(`not valid XML: ${valid.err.msg}`)
  const doc = parser.parse(xml) as Node
  let entries: unknown[]
  if (isNode(doc.rss) && isNode(doc.rss.channel)) entries = asArray(doc.rss.channel.item)
  else if (isNode(doc.feed)) entries = asArray(doc.feed.entry)
  else if (isNode(doc['rdf:RDF'])) entries = asArray(doc['rdf:RDF'].item)
  else throw new Error('not an RSS or Atom feed')

  const items: NewsItem[] = []
  for (const e of entries) {
    if (!isNode(e)) continue
    const url = linkOf(e)
    const title = cleanTitle(text(e.title))
    if (!url || !title) continue
    const body = text(e['content:encoded']) || text(e.content) || text(e.description) || text(e.summary)
    const short = text(e.description) || text(e.summary) || body
    const published = Math.min(dateOf(e) ?? ctx.now, ctx.now)
    items.push({
      id: createHash('sha1').update(url).digest('hex'),
      title,
      summary: summarize(short),
      url,
      source: ctx.source,
      lang: ctx.lang,
      published,
      image: imageOf(e, body || short),
    })
  }
  return items
}
