import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import {
  flexRender, getCoreRowModel, getFilteredRowModel, getSortedRowModel, useReactTable,
  type RowSelectionState, type SortingState, type VisibilityState,
} from '@tanstack/react-table'
import { STATUSES, type Book } from '../sync/model'
import type { LibraryStore } from '../sync/store'
import { BulkBar, type BulkActions } from './BulkBar'
import {
  COLUMN_LABELS, COLUMN_STORAGE_KEY, DEFAULT_VISIBILITY, makeColumns, type ColumnId,
} from './columns'
import {
  allTags, countByStatus, matches, NO_FILTERS, plural, STATUS_LABELS, type Filters, type LentFilter,
} from './filters'

function loadVisibility(): VisibilityState {
  try {
    const stored = JSON.parse(window.localStorage.getItem(COLUMN_STORAGE_KEY) ?? '{}') as Record<string, unknown>
    const out: VisibilityState = { ...DEFAULT_VISIBILITY }
    for (const id of Object.keys(DEFAULT_VISIBILITY)) if (typeof stored[id] === 'boolean') out[id] = stored[id]
    return out
  } catch {
    return { ...DEFAULT_VISIBILITY }
  }
}

function saveVisibility(v: VisibilityState) {
  try {
    window.localStorage.setItem(COLUMN_STORAGE_KEY, JSON.stringify(v))
  } catch {
    // Not kept this time; the columns still change.
  }
}

/** Whether a key press is meant for a field (a letter typed), not for the page. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return true
  return target.tagName === 'INPUT' && !['checkbox', 'radio', 'button'].includes((target as HTMLInputElement).type)
}

function GoalEditor({ store, year }: { store: LibraryStore; year: number }) {
  const goal = useSyncExternalStore(store.subscribe, () => store.goal(year))
  const [draft, setDraft] = useState<string | null>(null)

  const commit = () => {
    if (draft === null) return
    setDraft(null)
    const n = Number(draft)
    // An empty or odd value goes back to the saved one.
    if (draft.trim() === '' || !Number.isInteger(n) || n < 0 || n === goal) return
    store.edit('challenges', String(year), { goal: n })
  }

  return (
    <label className="goal">
      <span>{year} goal:</span>
      <input
        type="number"
        min={0}
        aria-label={`${year} goal`}
        value={draft ?? (goal === null ? '' : String(goal))}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
          else if (e.key === 'Escape') setDraft(null)
        }}
      />
    </label>
  )
}

export type LibraryPageProps = {
  store: LibraryStore
  /** The year whose goal is shown; this year when left out. */
  year?: number
  onAdd: () => void
  onOpen: (id: string) => void
}

/** Every book in a table: sort, search, filter, choose several and change them together. */
export function LibraryPage({ store, year = new Date().getFullYear(), onAdd, onOpen }: LibraryPageProps) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  // Newest first until a column is chosen.
  const data = useMemo(() => books.slice().sort((a, b) => b.dateAdded - a.dateAdded), [books])
  const columns = useMemo(() => makeColumns(onOpen), [onOpen])

  const [sorting, setSorting] = useState<SortingState>([])
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({})
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>(loadVisibility)
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const searchRef = useRef<HTMLInputElement>(null)

  const table = useReactTable({
    data,
    columns,
    state: { sorting, rowSelection, columnVisibility, globalFilter: filters },
    onSortingChange: setSorting,
    onRowSelectionChange: setRowSelection,
    onColumnVisibilityChange: (update) => {
      const next = typeof update === 'function' ? update(columnVisibility) : update
      setColumnVisibility(next)
      saveVisibility(next)
    },
    onGlobalFilterChange: setFilters,
    // One column stands for the whole book: the filters read the book, not a cell.
    getColumnCanGlobalFilter: (column) => column.id === 'title',
    globalFilterFn: (row, _columnId, value: Filters) => matches(row.original, value),
    getRowId: (book: Book) => book.id,
    sortDescFirst: false,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getSortedRowModel: getSortedRowModel(),
  })

  // Only books you can see are changed.
  const selected = table.getFilteredSelectedRowModel().rows.map((r) => r.original)
  const tags = useMemo(() => allTags(books), [books])
  const counts = useMemo(() => countByStatus(books), [books])
  const readThisYear = useMemo(
    () => books.filter((b) => b.status === 'READ' && b.dateRead !== null && new Date(b.dateRead).getFullYear() === year).length,
    [books, year],
  )

  const remove = useCallback(() => {
    if (selected.length === 0) return
    if (!window.confirm(`Delete ${plural(selected.length, 'book')}?`)) return
    for (const b of selected) store.remove('books', b.id)
    setRowSelection({})
  }, [selected, store])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) return
      if (e.key === '/') {
        e.preventDefault()
        searchRef.current?.focus()
      } else if (e.key === 'n') {
        e.preventDefault()
        onAdd()
      } else if (e.key === 'Delete') {
        remove()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onAdd, remove])

  // Each change goes through `edit` for each book, and only where the book would change: the rest is not stamped.
  const bulk: BulkActions = {
    setStatus: (status) => {
      for (const b of selected) if (b.status !== status) store.edit('books', b.id, { status })
    },
    addTag: (raw) => {
      const tag = raw.trim()
      if (tag === '' || tag.includes('\n')) return
      for (const b of selected) if (!b.tags.includes(tag)) store.edit('books', b.id, { tags: [...b.tags, tag] })
    },
    removeTag: (raw) => {
      const tag = raw.trim()
      for (const b of selected) if (b.tags.includes(tag)) store.edit('books', b.id, { tags: b.tags.filter((t) => t !== tag) })
    },
    setSeries: (rawSeries, rawNumber) => {
      const series = rawSeries.trim() === '' ? null : rawSeries.trim()
      const number = rawNumber.trim()
      for (const b of selected) {
        const fields: Record<string, unknown> = {}
        if (b.series !== series) fields.series = series
        if (number !== '' && b.seriesNumber !== number) fields.series_number = number
        if (Object.keys(fields).length > 0) store.edit('books', b.id, fields)
      }
    },
    lend: (raw) => {
      const to = raw.trim()
      if (to === '') return
      const now = Date.now()
      for (const b of selected) if (b.lentTo !== to) store.edit('books', b.id, { lent_to: to, lent_on: now })
    },
    markReturned: () => {
      for (const b of selected) if (b.lentTo !== null || b.lentOn !== null) store.edit('books', b.id, { lent_to: null, lent_on: null })
    },
    remove,
  }

  const rows = table.getRowModel().rows
  const filtered = filters.query !== '' || filters.status !== '' || filters.tag !== '' || filters.lent !== ''
  const setFilter = <K extends keyof Filters>(key: K, value: Filters[K]) => setFilters((f) => ({ ...f, [key]: value }))

  return (
    <main className="library-page">
      <header className="library-head">
        <h1>Library</h1>
        <p className="counts" aria-label="Counts">
          <span>{plural(books.length, 'book')}</span>
          {STATUSES.map((s) => (
            <span key={s}>
              {STATUS_LABELS[s]} · {plural(counts[s], 'book')}
            </span>
          ))}
        </p>
        <GoalEditor store={store} year={year} />
        <span className="read-count">{readThisYear} read in {year}</span>
      </header>

      <div className="toolbar">
        <input
          ref={searchRef}
          type="search"
          aria-label="Search"
          placeholder="Search title, author or ISBN  ( / )"
          value={filters.query}
          onChange={(e) => setFilter('query', e.target.value)}
        />
        <select
          aria-label="Filter by status"
          value={filters.status}
          onChange={(e) => setFilter('status', STATUSES.find((s) => s === e.target.value) ?? '')}
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_LABELS[s]}
            </option>
          ))}
        </select>
        <select aria-label="Filter by tag" value={filters.tag} onChange={(e) => setFilter('tag', e.target.value)}>
          <option value="">All tags</option>
          {tags.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        <select
          aria-label="Filter by lending"
          value={filters.lent}
          onChange={(e) => setFilter('lent', (e.target.value === 'out' || e.target.value === 'in' ? e.target.value : '') as LentFilter)}
        >
          <option value="">Lent or not</option>
          <option value="out">Lent out</option>
          <option value="in">Not lent</option>
        </select>
        {filtered && (
          <button type="button" onClick={() => setFilters(NO_FILTERS)}>
            Clear filters
          </button>
        )}
        <details className="columns">
          <summary>Columns</summary>
          <div className="columns-list">
            {table
              .getAllLeafColumns()
              .filter((c) => c.getCanHide())
              .map((c) => (
                <label key={c.id} className="check">
                  <input type="checkbox" checked={c.getIsVisible()} onChange={c.getToggleVisibilityHandler()} />
                  {COLUMN_LABELS[c.id as ColumnId]}
                </label>
              ))}
          </div>
        </details>
        <button type="button" className="primary" onClick={onAdd}>
          Add a book
        </button>
      </div>

      {selected.length > 0 && <BulkBar count={selected.length} tags={tags} actions={bulk} />}

      <div className="table-wrap">
        <table className="library-table">
          <thead>
            {table.getHeaderGroups().map((group) => (
              <tr key={group.id}>
                {group.headers.map((h) => {
                  const dir = h.column.getIsSorted()
                  return (
                    <th
                      key={h.id}
                      scope="col"
                      aria-sort={dir === 'asc' ? 'ascending' : dir === 'desc' ? 'descending' : h.column.getCanSort() ? 'none' : undefined}
                    >
                      {h.column.getCanSort() ? (
                        <button type="button" className="sort" onClick={h.column.getToggleSortingHandler()}>
                          {flexRender(h.column.columnDef.header, h.getContext())}
                          <span aria-hidden="true">{dir === 'asc' ? ' ▲' : dir === 'desc' ? ' ▼' : ''}</span>
                        </button>
                      ) : (
                        flexRender(h.column.columnDef.header, h.getContext())
                      )}
                    </th>
                  )
                })}
              </tr>
            ))}
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-selected={row.getIsSelected() || undefined}>
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && (
          <p className="empty">{books.length === 0 ? 'No books yet. Press n to add one.' : 'No book matches.'}</p>
        )}
      </div>
    </main>
  )
}
