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

function stripTags(s: string): string {
  return s
    .replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<\/?(p|br|div|li|ul|ol|h[1-6]|blockquote|tr|table)\b[^>]*>/gi, ' ')
    .replace(/<[^>]*>/g, '')
}

/** Plain text from an HTML fragment: tags and scripts gone, entities decoded, whitespace collapsed. */
export function toPlainText(html: string): string {
  // A second pass catches markup that only appears once the entities are decoded.
  return stripTags(decodeEntities(stripTags(html))).replace(/\s+/g, ' ').trim()
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
    return u.protocol === 'https:' ? u.toString() : null
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

function imageOf(entry: Node, bodyHtml: string): string | null {
  const candidates: string[] = []
  for (const m of asArray(entry['media:content'])) {
    const type = attr(m, 'type')
    const medium = attr(m, 'medium')
    if ((!type && !medium) || type.startsWith('image/') || medium === 'image') candidates.push(attr(m, 'url'))
  }
  for (const m of asArray(entry['media:thumbnail'])) candidates.push(attr(m, 'url'))
  for (const e of asArray(entry.enclosure)) {
    if (attr(e, 'type').startsWith('image/')) candidates.push(attr(e, 'url'))
  }
  for (const l of asArray(entry.link)) {
    if (attr(l, 'rel') === 'enclosure' && attr(l, 'type').startsWith('image/')) candidates.push(attr(l, 'href'))
  }
  const img = /<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']/i.exec(bodyHtml)
  if (img) candidates.push(decodeEntities(img[1]))
  for (const c of candidates) {
    const ok = httpsUrl(c)
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
    const title = cap(toPlainText(text(e.title)), MAX_TITLE)
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
