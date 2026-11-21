import type { ColumnDef, Row } from '@tanstack/react-table'
import { CoverImage } from '../covers/CoverImage'
import { formatDay } from '../text/dates'
import { searchKey } from '../text/keys'
import { STATUSES, type Book, type Status } from '../sync/model'
import { StatusSelect } from './StatusSelect'

/** Which columns show, kept in this browser. */
export const COLUMN_STORAGE_KEY = 'mybooks.library.columns'

/** Whether the library shows as a table or as covers, kept in this browser. */
export const VIEW_STORAGE_KEY = 'mybooks.library.view'

export type LibraryView = 'table' | 'covers'

export const COLUMN_LABELS = {
  cover: 'Cover',
  title: 'Title',
  authors: 'Authors',
  status: 'Status',
  score: 'Score',
  tags: 'Tags',
  series: 'Series #',
  started: 'Started',
  read: 'Read',
  publisher: 'Publisher',
  year: 'Year',
  pages: 'Pages',
  lentTo: 'Lent to',
  added: 'Added',
} as const

export type ColumnId = keyof typeof COLUMN_LABELS

/** What shows before the person chooses: the rarely read details start hidden. */
export const DEFAULT_VISIBILITY: Record<ColumnId, boolean> = {
  cover: true, title: true, authors: true, status: true, score: true, tags: true, series: true, started: false,
  read: true, publisher: false, year: false, pages: false, lentTo: true, added: false,
}

const date = (ms: number | null) => (ms === null ? '' : formatDay(ms))

/** A text to sort by (case and accents aside); nothing when empty, which sorts last either way. */
const sortText = (s: string | null): string | undefined => {
  const key = s === null ? '' : searchKey(s.trim())
  return key === '' ? undefined : key
}

const seriesNumber = (b: Book): number => {
  const n = parseFloat(b.seriesNumber ?? '')
  return Number.isNaN(n) ? Infinity : n
}

const bySeries = (a: Row<Book>, b: Row<Book>) => {
  const x = a.original
  const y = b.original
  const names = searchKey(x.series ?? '').localeCompare(searchKey(y.series ?? ''))
  if (names !== 0) return names
  const nx = seriesNumber(x)
  const ny = seriesNumber(y)
  return nx === ny ? 0 : nx < ny ? -1 : 1
}

const CHECKBOX = 'select'

/** What the cells can change: a book's status or score, or the tag the table is filtered by. */
export type CellActions = {
  setStatus: (book: Book, status: Status) => void
  /** The score from 1 to 5, or null to clear it. */
  setRating: (book: Book, rating: number | null) => void
  filterByTag: (tag: string) => void
}

const STARS = [1, 2, 3, 4, 5] as const

/** A book's name for a control: its title, and its authors when it has them (two books can share a title). */
const nameOf = (b: Book) => (b.authors.trim() === '' ? b.title : `${b.title} by ${b.authors}`)

/** The table's columns; [onOpen] opens a book from its title, [actions] change it from its row. */
export function makeColumns(onOpen: (id: string) => void, actions: CellActions): ColumnDef<Book>[] {
  const base = import.meta.env.BASE_URL
  const label = <K extends ColumnId>(id: K) => ({ id, header: COLUMN_LABELS[id], sortUndefined: 'last' as const })
  return [
    {
      id: CHECKBOX,
      enableSorting: false,
      enableHiding: false,
      header: ({ table }) => {
        const rows = table.getRowModel().rows
        const all = rows.length > 0 && rows.every((r) => r.getIsSelected())
        return (
          // The label is the 44 px tap target on a touch screen (see .check-hit); the box inside is still the control.
          <label className="check-hit">
            <input
              type="checkbox"
              aria-label="Select all shown"
              checked={all}
              onChange={() =>
                table.setRowSelection((old) => {
                  const next = { ...old }
                  for (const r of rows) {
                    if (all) delete next[r.id]
                    else next[r.id] = true
                  }
                  return next
                })
              }
            />
          </label>
        )
      },
      cell: ({ row }) => (
        <label className="check-hit">
          <input
            type="checkbox"
            aria-label={`Select ${row.original.title}`}
            checked={row.getIsSelected()}
            onChange={row.getToggleSelectedHandler()}
          />
        </label>
      ),
    },
    {
      ...label('cover'),
      accessorFn: (b) => (b.coverUrl ? 0 : 1),
      sortingFn: 'basic',
      cell: ({ row }) => <CoverImage book={row.original} compact className="thumb" />,
    },
    {
      ...label('title'),
      accessorFn: (b) => sortText(b.title),
      sortingFn: 'alphanumeric',
      cell: ({ row }) => (
        <a
          href={`${base}book/${row.original.id}`}
          onClick={(e) => {
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
            e.preventDefault()
            onOpen(row.original.id)
          }}
        >
          {row.original.title}
        </a>
      ),
    },
    { ...label('authors'), accessorFn: (b) => sortText(b.authors), sortingFn: 'alphanumeric', cell: ({ row }) => row.original.authors },
    {
      ...label('status'),
      accessorFn: (b) => STATUSES.indexOf(b.status),
      sortingFn: 'basic',
      cell: ({ row }) => {
        const b = row.original
        // The cloth tab stays: the select sits beside it, set into the cell without its own frame.
        return (
          <span className={`status status--${b.status.toLowerCase()}`}>
            <StatusSelect book={b} label={`Status of ${nameOf(b)}`} onCommit={(status) => actions.setStatus(b, status)} />
          </span>
        )
      },
    },
    {
      ...label('score'),
      accessorFn: (b) => b.rating ?? undefined,
      sortingFn: 'basic',
      cell: ({ row }) => {
        const b = row.original
        return (
          <span className="stars-edit">
            {STARS.map((n) => (
              <button
                key={n}
                type="button"
                className={`star-btn ${b.rating !== null && n <= b.rating ? 'star-btn--on' : ''}`}
                aria-label={`${String(n)} ${n === 1 ? 'star' : 'stars'} for ${nameOf(b)}`}
                aria-pressed={b.rating === n}
                onClick={() => actions.setRating(b, b.rating === n ? null : n)}
              >
                ★
              </button>
            ))}
          </span>
        )
      },
    },
    {
      ...label('tags'),
      accessorFn: (b) => sortText(b.tags.join(', ')),
      sortingFn: 'alphanumeric',
      cell: ({ row }) => (
        <span className="chips">
          {row.original.tags.map((t) => (
            <button
              key={t}
              type="button"
              className="chip"
              title={`Show books tagged ${t}`}
              onClick={() => actions.filterByTag(t)}
            >
              {t}
            </button>
          ))}
        </span>
      ),
    },
    {
      ...label('series'),
      accessorFn: (b) => sortText(b.series),
      sortingFn: bySeries,
      cell: ({ row }) => {
        const b = row.original
        return b.series === null || b.series === '' ? '' : b.seriesNumber ? `${b.series} #${b.seriesNumber}` : b.series
      },
    },
    { ...label('started'), accessorFn: (b) => b.dateStarted ?? undefined, sortingFn: 'basic', cell: ({ row }) => date(row.original.dateStarted) },
    { ...label('read'), accessorFn: (b) => b.dateRead ?? undefined, sortingFn: 'basic', cell: ({ row }) => date(row.original.dateRead) },
    { ...label('publisher'), accessorFn: (b) => sortText(b.publisher), sortingFn: 'alphanumeric', cell: ({ row }) => row.original.publisher ?? '' },
    { ...label('year'), accessorFn: (b) => b.publishedYear ?? undefined, sortingFn: 'basic', cell: ({ row }) => row.original.publishedYear ?? '' },
    { ...label('pages'), accessorFn: (b) => b.pageCount ?? undefined, sortingFn: 'basic', cell: ({ row }) => row.original.pageCount ?? '' },
    { ...label('lentTo'), accessorFn: (b) => sortText(b.lentTo), sortingFn: 'alphanumeric', cell: ({ row }) => row.original.lentTo ?? '' },
    { ...label('added'), accessorFn: (b) => b.dateAdded, sortingFn: 'basic', cell: ({ row }) => date(row.original.dateAdded) },
  ]
}
