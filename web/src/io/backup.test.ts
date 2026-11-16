import { readFileSync } from 'node:fs'
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate'
import { describe, expect, it } from 'vitest'
import type { Book, Quote } from '../sync/model'
import { blobBytes } from './blobBytes'
import { BackupFormatException, parseBackup, writeBackup } from './backup'

const json = readFileSync('test/fixtures/backup.json', 'utf8')
const bytes = (text: string) => strToU8(text)

const book = (id: string, over: Partial<Book> = {}): Book => ({
  id, isbn: null, title: 'T', authors: 'A', publisher: null, publishedYear: null, pageCount: null, coverUrl: null,
  description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 5, dateRead: null, dateStarted: null,
  currentPage: null, progressPercent: null, review: '', tags: [], series: null, seriesNumber: null, lentTo: null,
  lentOn: null, deleted: false, ...over,
})

describe('parseBackup', () => {
  it('readsVersion1Json', () => {
    const out = parseBackup(bytes(json))
    expect(out.books.map((b) => b.title)).toEqual(['Dune', 'Good Omens'])
    expect(out.books[0]).toMatchObject({
      isbn: '9780441013593', status: 'READ', rating: 5, tags: ['classics', 'sci-fi'], series: 'Dune', seriesNumber: '1',
      dateRead: 1710460800000, review: 'A classic.', notes: 'Reread in winter.', coverUrl: 'cover:photo-dune.jpg',
    })
    expect(out.books[1]).toMatchObject({ currentPage: 120, progressPercent: 30, lentTo: 'Ana', lentOn: 1735862400000 })
    expect(out.challenges).toEqual([{ year: 2025, goal: 24 }])
    expect(out.quotes.map((q) => [q.bookId === out.books[0].id, q.text, q.page, q.position])).toEqual([
      [true, 'Fear is the mind-killer.', 12, null],
      [true, 'The spice must flow.', null, 'ch3'],
    ])
    expect(out.covers.size).toBe(0)
    expect(out.exportedAt).toBe(1763251200000)
    expect(new Set(out.books.map((b) => b.id)).size).toBe(2)
  })

  it('fillsDefaultsForAnOldBackup', () => {
    const out = parseBackup(bytes('{"version":1,"books":[{"title":"Old","isbn":"9780441013593"}]}'))
    expect(out.books[0]).toMatchObject({
      title: 'Old', authors: '', status: 'TO_READ', notes: '', review: '', tags: [], dateAdded: 0, rating: null,
    })
    expect(out.challenges).toEqual([])
  })

  it('refusesWhatIsNotAVersion1Backup', () => {
    expect(() => parseBackup(bytes('{"version":2,"books":[]}'))).toThrow(BackupFormatException)
    expect(() => parseBackup(bytes('{ not json'))).toThrow(BackupFormatException)
    expect(() => parseBackup(bytes('[1,2]'))).toThrow(BackupFormatException)
    expect(() => parseBackup(bytes('{"version":1,"books":[{"title":"x","status":"LOST"}]}'))).toThrow(
      BackupFormatException,
    )
    expect(() => parseBackup(zipSync({ 'other.txt': bytes('hi') }))).toThrow('Not a MyBooks backup file')
  })

  it('readsTheZipWithItsCovers', async () => {
    const zip = zipSync({
      'library.json': bytes(json),
      'covers/photo-dune.jpg': new Uint8Array([1, 2, 3]),
      'covers/../../evil.jpg': new Uint8Array([9]),
      'covers/sub/dir.jpg': new Uint8Array([9]),
    })
    const out = parseBackup(zip)
    expect(out.books).toHaveLength(2)
    // Only plain file names under covers/ are kept.
    expect([...out.covers.keys()]).toEqual(['photo-dune.jpg'])
    expect([...(await blobBytes(out.covers.get('photo-dune.jpg') as Blob))]).toEqual([1, 2, 3])
  })
})

describe('writeBackup', () => {
  const quote = (bookId: string): Quote => ({ id: 'q1', bookId, text: 'Hello', page: 3, dateAdded: 9, position: null, deleted: false })

  it('exportMatchesTheAppsFormat', async () => {
    const books = [
      book('b1', { title: 'Dune', isbn: '9780441013593', coverUrl: 'photo', tags: ['a'], status: 'READ', rating: 4 }),
      book('b2', { coverUrl: 'https://example.com/c.jpg' }),
      book('b3', { coverUrl: 'photo' }), // its photo could not be fetched
    ]
    const covers = new Map([['b1', new Uint8Array([7, 8])]])
    const blob = writeBackup({ books, quotes: [quote('b1')], challenges: [{ year: 2025, goal: 20 }], covers }, 1_700_000_000_000)

    const entries = unzipSync(await blobBytes(blob))
    expect(Object.keys(entries).sort()).toEqual(['covers/b1.jpg', 'library.json'])
    expect([...entries['covers/b1.jpg']]).toEqual([7, 8])
    const file = JSON.parse(strFromU8(entries['library.json'])) as Record<string, unknown>
    // The app's BackupFile, in its order.
    expect(Object.keys(file)).toEqual(['version', 'exportedAt', 'books', 'challenges', 'hiddenSuggestions'])
    expect(file).toMatchObject({ version: 1, exportedAt: 1_700_000_000_000, challenges: [{ year: 2025, goal: 20 }], hiddenSuggestions: [] })
    const written = file.books as Record<string, unknown>[]
    // The app's BackupBook, in its order (the id is not part of it).
    expect(Object.keys(written[0])).toEqual([
      'isbn', 'title', 'authors', 'publisher', 'publishedYear', 'pageCount', 'coverUrl', 'description', 'status', 'rating',
      'notes', 'dateAdded', 'dateRead', 'dateStarted', 'currentPage', 'progressPercent', 'review', 'shareId',
      'shareExcluded', 'tags', 'series', 'seriesNumber', 'lentTo', 'lentOn', 'quotes', 'ebook', 'bookmarks',
    ])
    expect(written[0]).toMatchObject({
      coverUrl: 'cover:b1.jpg', status: 'READ', rating: 4, tags: ['a'], shareId: null, shareExcluded: false, ebook: null,
      bookmarks: [], quotes: [{ text: 'Hello', page: 3, dateAdded: 9, position: null }],
    })
    expect(Object.keys((written[0].quotes as object[])[0])).toEqual(['text', 'page', 'dateAdded', 'position'])
    expect(written[1].coverUrl).toBe('https://example.com/c.jpg')
    expect(written[2].coverUrl).toBeNull()
  })

  it('leavesOutDeletedBooksAndTheirQuotes', async () => {
    const blob = writeBackup(
      { books: [book('a'), book('gone', { deleted: true })], quotes: [quote('gone')], challenges: [], covers: new Map() }, 1,
    )
    const out = parseBackup(await blobBytes(blob))
    expect(out.books).toHaveLength(1)
    expect(out.quotes).toEqual([])
  })

  it('readsBackWhatItWrote', async () => {
    const books = [book('a', { title: 'One', coverUrl: 'photo', review: 'r', notes: 'n', tags: ['x', 'y'] }), book('b', { title: 'Two' })]
    const blob = writeBackup(
      { books, quotes: [quote('a')], challenges: [{ year: 2024, goal: 5 }], covers: new Map([['a', new Uint8Array([1])]]) }, 1,
    )
    const out = parseBackup(await blobBytes(blob))
    const strip = (b: Book) => ({ ...b, id: '' })
    expect(out.books.map(strip)).toEqual([strip({ ...books[0], coverUrl: 'cover:a.jpg' }), strip(books[1])])
    expect(out.quotes[0]).toMatchObject({ bookId: out.books[0].id, text: 'Hello', page: 3, dateAdded: 9 })
    expect(out.challenges).toEqual([{ year: 2024, goal: 5 }])
    expect([...out.covers.keys()]).toEqual(['a.jpg'])
  })

  it('anOversizedBackupSaysTooLarge', () => {
    const tooLarge = 'This backup file is too large to import.'
    const stored = zipSync({ 'library.json': [bytes(json), { level: 0 }] })
    expect(() => parseBackup(stored, { maxEntryBytes: 100 })).toThrow(tooLarge)
    expect(() => parseBackup(zipSync({ 'library.json': bytes(json), 'covers/a.jpg': new Uint8Array(50) }), { maxTotalBytes: json.length })).toThrow(tooLarge)
    // A damaged one still says so.
    expect(() => parseBackup(stored.slice(0, stored.length - 30))).toThrow('This backup file is damaged.')
  })

  it('aCorruptBackupSaysItIsDamaged', () => {
    const damaged = 'This backup file is damaged.'
    const stored = zipSync({ 'library.json': [bytes(json), { level: 0 }] })

    // A flipped byte inside the stored data: the CRC no longer matches (the JSON may still parse).
    const flipped = stored.slice()
    flipped[30 + 'library.json'.length + 5] ^= 0x20
    expect(() => parseBackup(flipped)).toThrow(damaged)

    // Cut short: the end of the archive is gone.
    expect(() => parseBackup(stored.slice(0, stored.length - 30))).toThrow(damaged)

    // A compressed entry with damaged data.
    const packed = zipSync({ 'library.json': bytes(json.repeat(20)) })
    const broken = packed.slice()
    for (let i = 40; i < 60; i++) broken[i] ^= 0xff
    expect(() => parseBackup(broken)).toThrow(damaged)

    // An intact one still reads.
    expect(parseBackup(stored).books).toHaveLength(2)
  })
})
