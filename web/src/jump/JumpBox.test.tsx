import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Book } from '../sync/model'
import { JumpBox } from './JumpBox'

function book(id: string, over: Partial<Book> = {}): Book {
  return {
    id, isbn: null, title: `Book ${id}`, authors: 'Author', publisher: null, publishedYear: null, pageCount: null,
    coverUrl: null, description: null, status: 'TO_READ', rating: null, notes: '', dateAdded: 7, dateRead: null,
    dateStarted: null, currentPage: null, progressPercent: null, review: '', tags: [], series: null,
    seriesNumber: null, lentTo: null, lentOn: null, deleted: false, ...over,
  }
}

const books = [
  book('1', { title: 'Blindness', authors: 'José Saramago', status: 'READ', isbn: '9789722015981' }),
  book('2', { title: 'The Stone Raft', authors: 'José Saramago', status: 'READING' }),
  book('3', { title: 'Emma', authors: 'Jane Austen' }),
]

describe('JumpBox', () => {
  it('ctrlKOpensTheJumpBox', async () => {
    const user = userEvent.setup()
    render(<JumpBox books={books} onOpen={vi.fn()} onAddIsbn={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.keyboard('{Control>}k{/Control}')
    expect(screen.getByRole('dialog', { name: 'Jump to a book' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Jump to a book' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    // The hint in the app bar opens it too.
    await user.click(screen.getByRole('button', { name: /Jump to a book/ }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('typingFindsBooksAndEnterOpensOne', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    render(<JumpBox books={books} onOpen={onOpen} onAddIsbn={vi.fn()} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), 'jose')

    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(2)
    expect(options[0]).toHaveTextContent('Blindness')
    expect(options[0]).toHaveTextContent('José Saramago')
    expect(options[0]).toHaveTextContent('Read')
    expect(options[0]).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{ArrowDown}')
    expect(options[1]).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Enter}')
    expect(onOpen).toHaveBeenCalledWith('2')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('showsAtMostEightBooks', async () => {
    const user = userEvent.setup()
    const many = Array.from({ length: 12 }, (_, i) => book(String(i), { title: `Volume ${String(i)}` }))
    render(<JumpBox books={many} onOpen={vi.fn()} onAddIsbn={vi.fn()} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), 'volume')
    expect(screen.getAllByRole('option')).toHaveLength(8)
  })

  it('anUnknownIsbnOffersToAddIt', async () => {
    const user = userEvent.setup()
    const onAddIsbn = vi.fn()
    const onOpen = vi.fn()
    render(<JumpBox books={[books[1]]} onOpen={onOpen} onAddIsbn={onAddIsbn} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), '978-972-20-1598-1')
    expect(screen.getAllByRole('option')[0]).toHaveTextContent('Add ISBN 9789722015981')
    await user.keyboard('{Enter}')
    expect(onAddIsbn).toHaveBeenCalledWith('9789722015981')
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('aKnownIsbnOpensTheBookInstead', async () => {
    const user = userEvent.setup()
    render(<JumpBox books={books} onOpen={vi.fn()} onAddIsbn={vi.fn()} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), '9789722015981')
    expect(screen.getAllByRole('option')[0]).toHaveTextContent('Blindness')
    expect(screen.queryByText(/Add ISBN/)).not.toBeInTheDocument()
  })

  it('aHyphenatedIsbnFindsTheBook', async () => {
    const user = userEvent.setup()
    render(<JumpBox books={books} onOpen={vi.fn()} onAddIsbn={vi.fn()} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), '978-972-20-1598-1')
    expect(screen.getAllByRole('option')[0]).toHaveTextContent('Blindness')
    expect(screen.queryByText(/Add ISBN/)).not.toBeInTheDocument()
  })

  it('keepsFocusAndSaysHowManyMatch', async () => {
    const user = userEvent.setup()
    render(<JumpBox books={books} onOpen={vi.fn()} onAddIsbn={vi.fn()} />)
    await user.keyboard('{Control>}k{/Control}')
    await user.type(screen.getByRole('combobox'), 'jose')
    expect(screen.getByRole('status')).toHaveTextContent('2 books')
    // A click on the box's own text does not take the focus away.
    await user.click(screen.getByRole('listbox', { name: 'Matching books' }).parentElement!)
    expect(screen.getByRole('combobox')).toHaveFocus()
    await user.clear(screen.getByRole('combobox'))
    await user.type(screen.getByRole('combobox'), 'zzz')
    expect(screen.getByRole('status')).toHaveTextContent('No book matches.')
  })

  it('escapeClosesEvenWhenTheFocusIsElsewhere', async () => {
    const user = userEvent.setup()
    render(<><button>Elsewhere</button><JumpBox books={books} onOpen={vi.fn()} onAddIsbn={vi.fn()} /></>)
    await user.keyboard('{Control>}k{/Control}')
    screen.getByRole('button', { name: 'Elsewhere' }).focus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
