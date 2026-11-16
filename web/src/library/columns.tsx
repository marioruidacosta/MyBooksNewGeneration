import type { ColumnDef, Row } from '@tanstack/react-table'
import { CoverImage } from '../covers/CoverImage'
import { searchKey } from '../text/keys'
import { STATUSES, type Book } from '../sync/model'
import { STATUS_LABELS } from './filters'

/** Which columns show, kept in this browser. */
export const COLUMN_STORAGE_KEY = 'mybooks.library.columns'

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

const date = (ms: number | null) => (ms === null ? '' : new Date(ms).toLocaleDateString())

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

/** The table's columns; [onOpen] opens a book from its title. */
export function makeColumns(onOpen: (id: string) => void): ColumnDef<Book>[] {
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
        )
      },
      cell: ({ row }) => (
        <input
          type="checkbox"
          aria-label={`Select ${row.original.title}`}
          checked={row.getIsSelected()}
          onChange={row.getToggleSelectedHandler()}
        />
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
      cell: ({ row }) => STATUS_LABELS[row.original.status],
    },
    { ...label('score'), accessorFn: (b) => b.rating ?? undefined, sortingFn: 'basic', cell: ({ row }) => row.original.rating ?? '' },
    {
      ...label('tags'),
      accessorFn: (b) => sortText(b.tags.join(', ')),
      sortingFn: 'alphanumeric',
      cell: ({ row }) => row.original.tags.join(', '),
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
