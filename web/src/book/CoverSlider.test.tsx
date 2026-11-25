import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Book } from '../sync/model'
import { CoverSlider } from './CoverSlider'
import type { AuthorInfo } from './wikipediaAuthor'

function book(over: Partial<Book> = {}): Book {
  return {
    id: '1', isbn: '9780156007757', title: 'Blindness', authors: 'José Saramago', publisher: 'Caminho', publishedYear: 1995,
    pageCount: 310, coverUrl: null, description: null, status: 'READING', rating: null, notes: '', dateAdded: new Date(2026, 9, 27).getTime(),
    dateRead: null, dateStarted: new Date(2026, 10, 1).getTime(), currentPage: null, progressPercent: null, review: '', tags: [],
    series: null, seriesNumber: null, lentTo: null, lentOn: null, shelfId: null, shelfPosition: null, deleted: false, ...over,
  }
}

const saramago: AuthorInfo = {
  name: 'José Saramago',
  description: 'escritor português',
  extract: 'José de Sousa Saramago foi um escritor português.',
  photoUrl: 'https://upload.wikimedia.org/saramago.jpg',
  pageUrl: 'https://pt.m.wikipedia.org/wiki/Jos%C3%A9_Saramago',
}

describe('CoverSlider', () => {
  it('hasTheCoverItsDetailsAndItsAuthorWithThreeDots', () => {
    render(<CoverSlider book={book()} books={[book()]} fetchAuthor={vi.fn()} />)
    const dots = within(screen.getByRole('tablist', { name: 'Cover, details and author' })).getAllByRole('tab')
    expect(dots.map((d) => d.getAttribute('aria-label'))).toEqual(['Cover', 'Details', 'Author'])
    expect(dots[0]).toHaveAttribute('aria-selected', 'true')

    const details = screen.getByRole('tabpanel', { name: 'Details' })
    for (const [label, value] of [
      ['Publisher', 'Caminho'], ['Published', '1995'], ['Pages', '310'], ['ISBN', '9780156007757'],
      ['Added', '27 Oct 2026'], ['Started', '1 Nov 2026'],
    ]) {
      expect(within(details).getByText(label).nextElementSibling).toHaveTextContent(value)
    }
    expect(within(details).queryByText('Finished')).not.toBeInTheDocument()
  })

  it('theAuthorIsLookedUpOnlyWhenShownWithTheirOtherBooks', async () => {
    const user = userEvent.setup({ delay: null })
    const fetchAuthor = vi.fn(async () => saramago)
    const onOpenBook = vi.fn()
    const books = [
      book(),
      // The same person with the name the other way round, as catalogues file them.
      book({ id: '2', title: 'Seeing', authors: 'Saramago José', status: 'TO_READ' }),
      book({ id: '3', title: 'The Cave', status: 'READ', rating: 4 }),
      book({ id: '4', title: 'Stoner', authors: 'John Williams' }),
    ]
    render(<CoverSlider book={books[0]} books={books} onOpenBook={onOpenBook} fetchAuthor={fetchAuthor} />)
    expect(fetchAuthor).not.toHaveBeenCalled()

    await user.click(screen.getByRole('tab', { name: 'Author' }))

    expect(screen.getByRole('tab', { name: 'Author' })).toHaveAttribute('aria-selected', 'true')
    expect(fetchAuthor).toHaveBeenCalledWith('José Saramago')
    const panel = screen.getByRole('tabpanel', { name: 'Author' })
    expect(await within(panel).findByText(saramago.extract)).toBeInTheDocument()
    expect(within(panel).getByText('escritor português')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'Wikipedia' })).toHaveAttribute('href', saramago.pageUrl)
    expect(within(panel).getByText('In your library · 2 other books')).toBeInTheDocument()
    // Read first, then A to Z; a read book shows its stars, the others their status.
    const others = within(panel).getAllByRole('button')
    expect(others.map((b) => b.textContent)).toEqual(['The Cave★★★★', 'SeeingTo read'])
    await user.click(others[1])
    expect(onOpenBook).toHaveBeenCalledWith('2')
  })

  it('theDetailsAddWhatWikidataKnowsWhenFirstShown', async () => {
    const user = userEvent.setup({ delay: null })
    const fetchFacts = vi.fn(async () => ({
      firstPublished: '1995', originalTitle: 'Ensaio sobre a Cegueira', originalLanguage: 'português', genres: ['ficção científica'],
      awards: ['Some Prize'], follows: null, followedBy: 'Seeing', pageUrl: 'https://www.wikidata.org/wiki/Q826428',
    }))
    render(<CoverSlider book={book()} books={[book()]} fetchAuthor={vi.fn()} fetchFacts={fetchFacts} />)
    expect(fetchFacts).not.toHaveBeenCalled()

    await user.click(screen.getByRole('tab', { name: 'Details' }))

    expect(fetchFacts).toHaveBeenCalledWith('Blindness', 'José Saramago')
    const details = screen.getByRole('tabpanel', { name: 'Details' })
    expect((await within(details).findByText('Followed by')).nextElementSibling).toHaveTextContent('Seeing')
    expect(within(details).getByText('Awards').nextElementSibling).toHaveTextContent('Some Prize')
    expect(within(details).getByText('Original title').nextElementSibling).toHaveTextContent('Ensaio sobre a Cegueira (português)')
    expect(within(details).getByRole('link', { name: 'Wikidata' })).toHaveAttribute('href', 'https://www.wikidata.org/wiki/Q826428')
  })

  it('aBookWithNoAuthorSaysSo', async () => {
    const user = userEvent.setup({ delay: null })
    const fetchAuthor = vi.fn()
    render(<CoverSlider book={book({ authors: '' })} books={[]} fetchAuthor={fetchAuthor} />)
    await user.click(screen.getByRole('tab', { name: 'Author' }))
    expect(screen.getByText('No author recorded for this book.')).toBeInTheDocument()
    expect(fetchAuthor).not.toHaveBeenCalled()
  })

  it('noBiographyFoundStillShowsTheirBooks', async () => {
    const user = userEvent.setup({ delay: null })
    render(<CoverSlider book={book()} books={[book()]} fetchAuthor={vi.fn(async () => null)} />)
    await user.click(screen.getByRole('tab', { name: 'Author' }))
    expect(await screen.findByText('No other books by them in your library')).toBeInTheDocument()
    expect(screen.queryByText(/Looking up/)).not.toBeInTheDocument()
  })
})
