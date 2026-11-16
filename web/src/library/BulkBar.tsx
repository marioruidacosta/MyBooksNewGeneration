import { useState } from 'react'
import { STATUSES, type Status } from '../sync/model'
import { STATUS_LABELS } from './filters'

export type BulkActions = {
  setStatus: (status: Status) => void
  addTag: (tag: string) => void
  removeTag: (tag: string) => void
  setSeries: (series: string, number: string) => void
  lend: (to: string) => void
  markReturned: () => void
  remove: () => void
}

/** The changes for the selected books. `tags` are the tags in use, offered as you type. */
export function BulkBar({ count, tags, actions }: { count: number; tags: string[]; actions: BulkActions }) {
  const [tag, setTag] = useState('')
  const [series, setSeries] = useState('')
  const [number, setNumber] = useState('')
  const [lendTo, setLendTo] = useState('')

  return (
    <section className="bulk-bar" aria-label="Changes for the selected books">
      <strong>{count} selected</strong>

      <select
        aria-label="Set status"
        value=""
        onChange={(e) => {
          const status = STATUSES.find((s) => s === e.target.value)
          if (status) actions.setStatus(status)
        }}
      >
        <option value="">Set status…</option>
        {STATUSES.map((s) => (
          <option key={s} value={s}>
            {STATUS_LABELS[s]}
          </option>
        ))}
      </select>

      <span className="bulk-group">
        <input aria-label="Tag" list="bulk-tags" placeholder="Tag" value={tag} onChange={(e) => setTag(e.target.value)} />
        <datalist id="bulk-tags">
          {tags.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
        <button type="button" disabled={tag.trim() === ''} onClick={() => actions.addTag(tag)}>
          Add tag
        </button>
        <button type="button" disabled={tag.trim() === ''} onClick={() => actions.removeTag(tag)}>
          Remove tag
        </button>
      </span>

      <span className="bulk-group">
        <input aria-label="Series name" placeholder="Series" value={series} onChange={(e) => setSeries(e.target.value)} />
        <input
          aria-label="Series number"
          className="narrow"
          placeholder="No."
          value={number}
          onChange={(e) => setNumber(e.target.value)}
        />
        <button type="button" onClick={() => actions.setSeries(series, number)}>
          Set series
        </button>
      </span>

      <span className="bulk-group">
        <input aria-label="Lend to" placeholder="Lend to" value={lendTo} onChange={(e) => setLendTo(e.target.value)} />
        <button type="button" disabled={lendTo.trim() === ''} onClick={() => actions.lend(lendTo)}>
          Lend
        </button>
        <button type="button" onClick={actions.markReturned}>
          Mark returned
        </button>
      </span>

      <button type="button" className="danger" onClick={actions.remove}>
        Delete
      </button>
    </section>
  )
}
