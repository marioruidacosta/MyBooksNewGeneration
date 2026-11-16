import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import type { Book } from '../sync/model'
import { GoodreadsFormatException, parseCsv, parseGoodreads, writeCsv, writeGoodreads } from './goodreads'

const fixture = readFileSync('test/fixtures/goodreads_library_export.csv', 'utf8')
const day = (y: number, m: number, d: number) => new Date(y, m - 1, d).getTime() // a local day, as Goodreads gives it
const books = parseGoodreads(fixture)
const withoutId = (b: Book): Omit<Book, 'id'> => {
  const { id: _id, ...rest } = b
  void _id
  return rest
}

describe('parseGoodreads', () => {
  it('readsEveryRow', () => {
    expect(books.map((b) => b.title)).toEqual(['Dune', 'Good Omens', 'Emma', 'Old Book', ''])
    expect(new Set(books.map((b) => b.id)).size).toBe(5)
  })

  it('goodreadsMapsShelvesToStatus', () => {
    expect(books.map((b) => b.status)).toEqual(['READ', 'READING', 'WISHLIST', 'TO_READ', 'TO_READ'])
    // Shelves other than the reading ones (and to-buy, a status here) are tags.
    expect(books[2].tags).toEqual(['classics'])
    expect(books[3].tags).toEqual(['abandoned'])
    expect(books[0].tags).toEqual([])
  })

  it('readsAReadBookWithEverything', () => {
    expect(withoutId(books[0])).toMatchObject({
      title: 'Dune', series: 'Dune', seriesNumber: '1', authors: 'Frank Herbert', isbn: '9780441013593', rating: 5,
      publisher: 'Ace', pageCount: 688, publishedYear: 2005, status: 'READ',
      dateRead: day(2024, 3, 15), dateAdded: day(2023, 12, 1), review: 'Loved it.\n\nSpice & "sand".', notes: 'my note',
    })
  })

  it('readsAnUnratedBookWithTwoAuthorsAndNoIsbn', () => {
    expect(books[1]).toMatchObject({ authors: 'Terry Pratchett, Neil Gaiman', rating: null, isbn: null, dateRead: null })
    expect(books[1].series).toBeNull()
  })

  it('keepsTheLinesOfAMultiLineReview', () => {
    expect(books[3].review).toBe('Line one\nline two')
  })

  it('roundsDecimalRatingsToWholeStars', () => {
    const rows = ['5.0', '4.25', '4.5', '3.0', '0'].map((r, i) => `t${String(i)},A,read,${r}`)
    const csv = ['Title,Author,Exclusive Shelf,My Rating', ...rows].join('\n')
    expect(parseGoodreads(csv).map((b) => b.rating)).toEqual([5, 4, 5, 3, null])
  })

  it('shortensALongReviewToTheLimit', () => {
    const csv = `Title,Author,Exclusive Shelf,My Review\nLong,A,read,${'x'.repeat(3000)}\n`
    expect(parseGoodreads(csv)[0].review).toHaveLength(2000)
  })

  it('ignoresAnImpossibleDate', () => {
    const csv = 'Title,Author,Exclusive Shelf,Date Read,Date Added\nA,B,read,2024/02/31,2024/13/01\n'
    expect(parseGoodreads(csv)[0]).toMatchObject({ dateRead: null, dateAdded: 0 })
  })

  it('refusesAFileThatIsNotGoodreads', () => {
    expect(() => parseGoodreads('name,age\nAna,3\n')).toThrow(GoodreadsFormatException)
    expect(() => parseGoodreads('')).toThrow(GoodreadsFormatException)
  })
})

describe('writeGoodreads', () => {
  const mine: Book[] = [
    { ...books[0], id: 'a', title: 'Dune, "the" novel', authors: 'Frank Herbert, Brian Herbert', notes: '', review: 'Great.\nReally <great> & long.' },
    { ...books[2], id: 'b', tags: ['classics', 'gift'], notes: '' },
    { ...books[1], id: 'c', status: 'READING', notes: '' },
    { ...books[3], id: 'd', status: 'TO_READ', tags: [], notes: '', review: '' },
  ]

  it('goodreadsRoundTrips', () => {
    const back = parseGoodreads(writeGoodreads(mine))
    // The same rows again (private notes aren't exported, and the ids are new).
    expect(back.map(withoutId)).toEqual(mine.map((b) => withoutId({ ...b, notes: '' })))
    // And writing what was read gives the same text.
    expect(writeGoodreads(back)).toBe(writeGoodreads(mine))
  })

  it('writesTheAppsColumnsAndRows', () => {
    const lines = writeGoodreads([books[0]]).split('\r\n')
    expect(lines[0]).toBe(
      'Title,Author,Additional Authors,ISBN,ISBN13,My Rating,Publisher,Number of Pages,Year Published,Date Read,Date Added,Bookshelves,Exclusive Shelf,My Review',
    )
    expect(lines[1]).toBe('"Dune (Dune, #1)",Frank Herbert,,,9780441013593,5,Ace,688,2005,2024/03/15,2023/12/01,,read,"Loved it.<br/><br/>Spice &amp; ""sand""."')
    expect(lines[2]).toBe('')
  })

  it('writesTagsAsShelfNamesAndToBuyForAWishlist', () => {
    const row = parseCsv(writeGoodreads([{ ...books[0], tags: ['Gift from Ana', 'sci-fi'], status: 'WISHLIST' }]))
    expect(row[1][row[0].indexOf('Bookshelves')]).toBe('gift-from-ana, sci-fi, to-buy')
    expect(row[1][row[0].indexOf('Exclusive Shelf')]).toBe('to-read')
  })

  it('leavesTheDateReadOutUnlessTheBookIsRead', () => {
    const row = parseCsv(writeGoodreads([{ ...books[0], status: 'READING' }]))
    expect(row[1][row[0].indexOf('Date Read')]).toBe('')
  })
})

describe('csv', () => {
  it('quotesCommasQuotesAndNewlines', () => {
    const rows = [['a,b', 'say "hi"', 'two\nlines', ''], ['plain', '', 'x', 'y']]
    expect(parseCsv(writeCsv(rows))).toEqual(rows)
    expect(parseCsv('a,b\r\nc,d\r\n')).toEqual([['a', 'b'], ['c', 'd']])
  })
})
