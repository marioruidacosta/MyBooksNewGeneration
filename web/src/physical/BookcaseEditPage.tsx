import { useId, useState, useSyncExternalStore } from 'react'
import { ConfirmDialog } from '../community/ui'
import type { LibraryStore } from '../sync/store'
import { BookcaseDrawing } from './BookcaseDrawing'
import {
  byRoom, MAX_BOOKCASE_NAME, MAX_COLUMNS, MAX_SHELVES, newBookcase, onShelf, shelfIds, withColumnAdded, withoutColumn, withoutShelf,
  withShelfAdded, type Bookcase,
} from './model'
import { clearPlaces } from './places'
import { useBookcases } from './useBookcases'

const NEW_COLUMNS = 2
const NEW_SHELVES = 4

type Removal = { title: string; confirm: string; count: number; run: () => void }

function Stepper({
  label, value, what, canLess, canMore, onLess, onMore,
}: {
  label: string
  value: number
  what: string
  canLess: boolean
  canMore: boolean
  onLess: () => void
  onMore: () => void
}) {
  return (
    <div className="stepper">
      <span>{label}</span>
      <span className="stepper__row">
        <button type="button" aria-label={`Fewer ${what}`} disabled={!canLess} onClick={onLess}>−</button>
        <strong>{value}</strong>
        <button type="button" aria-label={`More ${what}`} disabled={!canMore} onClick={onMore}>+</button>
      </span>
    </div>
  )
}

/** A new bookcase ([id] null) or an existing one. Nothing is saved until Save; the drawing follows. */
export function BookcaseEditPage({ store, id, onDone }: { store: LibraryStore; id: string | null; onDone: () => void }) {
  const books = useSyncExternalStore(store.subscribe, () => store.books())
  const { bookcases, save, remove } = useBookcases(store)
  const original = id === null ? null : bookcases.find((b) => b.id === id) ?? null
  const [draft, setDraft] = useState<Bookcase>(() => original ?? newBookcase('', '', NEW_COLUMNS, NEW_SHELVES))
  const [name, setName] = useState(original?.name ?? '')
  const [room, setRoom] = useState(original?.room ?? '')
  const [freed, setFreed] = useState<ReadonlySet<string>>(new Set())
  const [asking, setAsking] = useState<Removal | null>(null)
  const roomsId = useId()
  const readOnly = original?.readOnly === true
  const countOn = (shelves: string[]) => shelves.reduce((sum, s) => sum + onShelf(books, s).length, 0)

  const removeShelves = (title: string, confirm: string, shelves: string[], apply: () => void) => {
    const run = () => {
      setFreed((old) => new Set([...old, ...shelves]))
      apply()
    }
    const count = countOn(shelves)
    if (count === 0) run()
    else setAsking({ title, confirm, count, run })
  }

  const shelvesEach = draft.columns[0].shelves.length
  return (
    <main className="physical-page">
      <button type="button" className="quiet physical-back" onClick={onDone}>Back</button>
      <h1>{original ? 'Edit bookcase' : 'New bookcase'}</h1>
      {readOnly && <p className="error">Made by a newer version of MyBooks. Update the app to change it.</p>}
      <div className="bookcase-form">
        <label className="field">
          <span>Name</span>
          <input value={name} maxLength={MAX_BOOKCASE_NAME} disabled={readOnly} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>Room</span>
          <input list={roomsId} value={room} maxLength={MAX_BOOKCASE_NAME} disabled={readOnly} onChange={(e) => setRoom(e.target.value)} />
        </label>
        <datalist id={roomsId}>{byRoom(bookcases).map(([r]) => <option key={r} value={r} />)}</datalist>

        {!readOnly && !original && (
          <div className="stepper-row">
            <Stepper
              label="Columns" value={draft.columns.length} what="columns"
              canLess={draft.columns.length > 1} canMore={draft.columns.length < MAX_COLUMNS}
              onLess={() => setDraft((d) => withoutColumn(d, d.columns.length - 1))}
              onMore={() => setDraft((d) => withColumnAdded(d, d.columns[0].shelves.length))}
            />
            <Stepper
              label="Shelves" value={shelvesEach} what="shelves"
              canLess={shelvesEach > 1} canMore={shelvesEach < MAX_SHELVES}
              onLess={() => setDraft((d) => ({ ...d, columns: d.columns.map((c) => ({ ...c, shelves: c.shelves.slice(0, -1) })) }))}
              onMore={() => setDraft((d) => d.columns.reduce((next, _, c) => withShelfAdded(next, c), d))}
            />
          </div>
        )}

        {!readOnly && original && (
          <div className="column-editor">
            {draft.columns.map((column, c) => {
              const letter = String.fromCharCode(65 + c)
              return (
                <div key={column.id} className="column-editor__row">
                  <span>Column {letter} · {column.shelves.length === 1 ? '1 shelf' : `${String(column.shelves.length)} shelves`}</span>
                  <button
                    type="button"
                    aria-label={`Remove a shelf from column ${letter}`}
                    disabled={column.shelves.length <= 1}
                    onClick={() => {
                      const last = column.shelves[column.shelves.length - 1]
                      removeShelves('Remove this shelf?', 'Remove shelf', [last], () => setDraft((d) => withoutShelf(d, last)))
                    }}
                  >−</button>
                  <button
                    type="button"
                    aria-label={`Add a shelf to column ${letter}`}
                    disabled={column.shelves.length >= MAX_SHELVES}
                    onClick={() => setDraft((d) => withShelfAdded(d, c))}
                  >+</button>
                </div>
              )
            })}
            <div className="column-editor__actions">
              <button
                type="button"
                disabled={draft.columns.length >= MAX_COLUMNS}
                onClick={() => setDraft((d) => withColumnAdded(d, d.columns[d.columns.length - 1].shelves.length))}
              >Add a column</button>
              <button
                type="button"
                disabled={draft.columns.length <= 1}
                onClick={() => {
                  const last = draft.columns.length - 1
                  removeShelves('Remove this column?', 'Remove column', draft.columns[last].shelves, () => setDraft((d) => withoutColumn(d, last)))
                }}
              >Remove last column</button>
            </div>
          </div>
        )}

        <BookcaseDrawing bookcase={draft} books={books} labels />

        {!readOnly && (
          <button
            type="button"
            className="primary"
            disabled={name.trim() === '' || room.trim() === ''}
            onClick={() => {
              if (freed.size > 0) clearPlaces(store, freed)
              save({ ...draft, name: name.trim(), room: room.trim() })
              onDone()
            }}
          >Save bookcase</button>
        )}
        {!readOnly && original && (
          <button
            type="button"
            className="danger"
            onClick={() => setAsking({
              title: 'Delete this bookcase?',
              confirm: 'Delete',
              count: countOn(shelfIds(original)),
              run: () => {
                remove(original.id)
                onDone()
              },
            })}
          >Delete bookcase</button>
        )}
      </div>
      {asking && (
        <ConfirmDialog
          title={asking.title}
          body={asking.count === 0
            ? 'No books are on it.'
            : `${asking.count === 1 ? '1 book' : `${String(asking.count)} books`} will go to Without a place.`}
          confirm={asking.confirm}
          danger
          onConfirm={async () => asking.run()}
          onClose={() => setAsking(null)}
        />
      )}
    </main>
  )
}
