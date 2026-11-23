import { describe, expect, it } from 'vitest'
import type { Book } from '../sync/model'
import { authorKey, catalogTitleKey, findInLibrary, searchKey } from './keys'

const book = (over: Partial<Book>): Book => ({
  id: 'x', isbn: null, title: '', authors: '', publisher: null, publishedYear: null, pageCount: null,
  coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 0, dateRead: null,
  dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
  seriesNumber: null, lentTo: null, lentOn: null, shelfId: null, shelfPosition: null, deleted: false, ...over,
})

describe('keys', () => {
  it('keysMatchTheApp', () => {
    expect(searchKey('José Saramago')).toBe('jose saramago')
    expect(searchKey('Ética')).toBe('etica')
    expect(authorKey('Han Kang')).toBe(authorKey('Kang Han'))
    expect(authorKey('Han Kang')).toBe('han kang')
    expect(authorKey('Saramago, José')).toBe('jose saramago')
    expect(catalogTitleKey('Os Maias: episódios da vida romântica')).toBe(catalogTitleKey('Os Maias'))
    expect(catalogTitleKey('Ensaio sobre a Cegueira: romance')).toBe(catalogTitleKey('Ensaio Sobre a Cegueira'))
  })
})

describe('findInLibrary', () => {
  const library = [
    book({ id: 'a', isbn: '111', title: 'Os Maias', authors: 'Eça de Queirós' }),
    book({ id: 'b', isbn: null, title: 'Blindness', authors: 'José Saramago, Giovanni Pontiero' }),
  ]

  it('findsTheSameBook', () => {
    expect(findInLibrary({ isbn: '111', title: 'Other', authors: 'Nobody' }, library)?.id).toBe('a')
    expect(findInLibrary({ isbn: null, title: 'Blindness: a novel', authors: 'Jose Saramago' }, library)?.id).toBe('b')
    expect(findInLibrary({ isbn: '999', title: 'Blindness', authors: 'Saramago José' }, library)?.id).toBe('b')
  })

  it('two different ISBNs never match on the ISBN alone', () => {
    expect(findInLibrary({ isbn: '222', title: 'Another', authors: 'Someone' }, library)).toBeUndefined()
  })

  it('a different first author is a different book', () => {
    expect(findInLibrary({ title: 'Blindness', authors: 'Somebody Else' }, library)).toBeUndefined()
  })
})
