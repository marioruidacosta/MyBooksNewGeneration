import { describe, expect, it } from 'vitest'
import type { Book, Status } from '../sync/model'
import {
  byRoom, endPosition, findShelf, MAX_COLUMNS, MAX_SHELVES, newBookcase, onShelf, parseBookcase, positionBetween, renumbered,
  shelfIds, shelfLabel, toStored, withColumnAdded, withoutColumn, withoutPlace, withoutShelf, withShelfAdded, type Bookcase,
} from './model'

// The same text as PhysicalLibraryTest.kt: both apps read and write this format.
const STORED =
  '{"v":1,"name":"By the window","room":"Living room","columns":[{"id":"c1","shelves":["s1","s2"]},{"id":"c2","shelves":["s3"]}]}'

const windowCase: Bookcase = {
  id: 'x', name: 'By the window', room: 'Living room', readOnly: false,
  columns: [{ id: 'c1', shelves: ['s1', 's2'] }, { id: 'c2', shelves: ['s3'] }],
}

const ids = () => {
  let n = 0
  return () => `id${++n}`
}

const book = (id: string, title: string, shelfId: string | null = null, shelfPosition: number | null = null, status: Status = 'READ'): Book => ({
  id, isbn: null, title, authors: '', publisher: null, publishedYear: null, pageCount: null, coverUrl: null, description: null,
  status, rating: null, notes: '', dateAdded: 0, dateRead: null, dateStarted: null, currentPage: null, progressPercent: null,
  review: '', tags: [], series: null, seriesNumber: null, lentTo: null, lentOn: null, shelfId, shelfPosition, deleted: false,
})

describe('the stored format', () => {
  it('theStoredFormatIsExact', () => {
    expect(toStored(windowCase)).toBe(STORED)
    expect(parseBookcase('bookcase.x', STORED)).toEqual(windowCase)
  })

  it('unreadableValuesAreSkipped', () => {
    expect(parseBookcase('bookcase.x', 'nope')).toBeNull()
    expect(parseBookcase('bookcase.x', '')).toBeNull()
    expect(parseBookcase('bookcase.x', '{"v":1,"name":"n","room":"r","columns":[]}')).toBeNull()
    expect(parseBookcase('bookcase.x', '{"v":1,"name":"n","room":"r","columns":[{"id":"c","shelves":[]}]}')).toBeNull()
    expect(parseBookcase('bookcase.x', '{"v":1,"name":"n","room":"r","columns":[{"id":3,"shelves":["s"]}]}')).toBeNull()
    expect(parseBookcase('other.x', STORED)).toBeNull()
  })

  it('aNewerFormatIsReadOnly', () => {
    const c = parseBookcase('bookcase.x', '{"v":2,"name":"n","room":"r","depth":3,"columns":[{"id":"c","shelves":["s"],"width":2}]}')
    expect(c?.readOnly).toBe(true)
    expect(c && shelfIds(c)).toEqual(['s'])
  })

  it('blankRoomReadsAsNoRoom', () => {
    expect(parseBookcase('bookcase.x', '{"v":1,"name":"n","room":" ","columns":[{"id":"c","shelves":["s"]}]}')?.room).toBe('No room')
  })
})

describe('bookcases', () => {
  it('labels', () => {
    expect(shelfLabel(0, 1)).toBe('A2')
    expect(shelfLabel(2, 0)).toBe('C1')
    const place = findShelf([windowCase], 's2')
    expect(place?.label).toBe('A2')
    expect(place?.location).toBe('Living room › By the window › Shelf A2')
    expect(findShelf([windowCase], 'nope')).toBeNull()
    expect(findShelf([windowCase], null)).toBeNull()
  })

  it('newBookcaseClampsItsSize', () => {
    const c = newBookcase('  n ', ' r ', 11, 16, ids())
    expect([c.name, c.room]).toEqual(['n', 'r'])
    expect(c.columns).toHaveLength(MAX_COLUMNS)
    expect(c.columns.every((x) => x.shelves.length === MAX_SHELVES)).toBe(true)
    expect(new Set(shelfIds(c)).size).toBe(150)
    expect(newBookcase('x'.repeat(61), 'r', 1, 1, ids()).name).toHaveLength(60)
    expect(shelfIds(newBookcase('n', 'r', 0, 0, ids()))).toHaveLength(1)
  })

  it('eachColumnCanDiffer', () => {
    const more = withShelfAdded(windowCase, 1, ids())
    expect(more.columns[0].shelves).toEqual(['s1', 's2'])
    expect(more.columns[1].shelves).toEqual(['s3', 'id1'])
    const full = newBookcase('n', 'r', 1, MAX_SHELVES, ids())
    expect(withShelfAdded(full, 0, ids())).toEqual(full)
    const single = newBookcase('n', 'r', 2, 1, ids())
    expect(withoutShelf(single, single.columns[0].shelves[0])).toEqual(single)
    const one = newBookcase('n', 'r', 1, 2, ids())
    expect(withoutColumn(one, 0)).toEqual(one)
    expect(withColumnAdded(windowCase, 3, ids()).columns[2].shelves).toHaveLength(3)
    const wide = newBookcase('n', 'r', MAX_COLUMNS, 1, ids())
    expect(withColumnAdded(wide, 1, ids())).toEqual(wide)
  })

  it('removingAShelfKeepsOtherIds', () => {
    const after = withoutShelf(windowCase, 's1')
    expect(after.columns[0].shelves).toEqual(['s2'])
    expect(findShelf([after], 's2')?.label).toBe('A1')
  })

  it('roomsAndBookcasesSortByName', () => {
    const c = (name: string, room: string): Bookcase => ({ id: name, name, room, readOnly: false, columns: [{ id: `c${name}`, shelves: [`s${name}`] }] })
    const rooms = byRoom([c('zed', 'office'), c('Alpha', 'Office'), c('mid', 'Attic'), c('beta', 'office')])
    expect(rooms.map(([r]) => r.toLowerCase())).toEqual(['attic', 'office'])
    expect(rooms[1][1].map((x) => x.name)).toEqual(['Alpha', 'beta', 'zed'])
  })
})

describe('books on shelves', () => {
  it('shelfOrder', () => {
    const books = [
      book('1', 'Zola', 's1'), book('2', 'Austen', 's1'), book('3', 'Second', 's1', 2), book('4', 'First', 's1', 1),
      book('5', 'To buy', 's1', 0.5, 'WISHLIST'), book('6', 'Elsewhere', 's2', 1),
    ]
    expect(onShelf(books, 's1').map((b) => b.id)).toEqual(['4', '3', '2', '1'])
  })

  it('withoutPlaceRules', () => {
    const books = [
      book('1', 'No shelf'), book('2', 'Orphan', 'gone', 1), book('3', 'To buy', null, null, 'WISHLIST'),
      book('4', 'Ebook'), book('5', 'Placed', 's1', 1),
    ]
    expect(withoutPlace(books, [windowCase], new Set(['4'])).map((b) => b.id)).toEqual(['1', '2'])
  })

  it('endPosition', () => {
    expect(endPosition([])).toBe(1)
    expect(endPosition([book('1', 'a', 's', 3.5), book('2', 'b', 's', 1), book('3', 'c', 's')])).toBe(4.5)
    expect(endPosition([book('1', 'a', 's')])).toBe(1)
  })

  it('positionBetween', () => {
    expect(positionBetween(null, null)).toBe(1)
    expect(positionBetween(null, 2)).toBe(1)
    expect(positionBetween(2, null)).toBe(3)
    expect(positionBetween(1, 2)).toBe(1.5)
    expect(positionBetween(1, 1.0000001)).toBeNull()
  })

  it('renumberedKeepsTheVisibleOrder', () => {
    const shelf = onShelf([book('1', 'Zola', 's'), book('2', 'Austen', 's'), book('3', 'First', 's', 0.25)], 's')
    expect([...renumbered(shelf)]).toEqual([['3', 1], ['2', 2], ['1', 3]])
  })
})
