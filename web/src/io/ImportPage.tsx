import { useRef, useState } from 'react'
import type { ImageDecoder } from '../covers/coverUpload'
import type { CoverStorage } from '../covers/storage'
import type { LibraryStore } from '../sync/store'
import {
  backupExport, browserDownload, goodreadsExport, readImportFile, storagePhotos, type Download, type GetPhoto,
} from './exports'
import { applyPlan, planImport, previewText, type Plan } from './importPlan'

type Props = {
  store: LibraryStore
  /** Whose photos these are and where they are kept; without them a backup's photos are left out. */
  owner?: string
  storage?: CoverStorage
  /** Saves a file for the person; a test brings its own. */
  download?: Download
  getPhoto?: GetPhoto
  decode?: ImageDecoder
  now?: () => number
}

const count = (n: number, one: string, many: string) => `${String(n)} ${n === 1 ? one : many}`

/** Import a Goodreads CSV or a MyBooks backup (after a preview), and export either. */
export function ImportPage({ store, owner, storage, download = browserDownload, getPhoto, decode, now = Date.now }: Props) {
  const [plan, setPlan] = useState<Plan | null>(null)
  const [error, setError] = useState('')
  const [done, setDone] = useState('')
  const [busy, setBusy] = useState(false)
  const [exporting, setExporting] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const choose = async (file: File | undefined) => {
    setPlan(null)
    setError('')
    setDone('')
    if (!file) return
    setBusy(true)
    try {
      const at = now()
      const source = await readImportFile(file, at)
      setPlan(planImport(source, store, at))
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : "That file couldn't be read.")
    } finally {
      setBusy(false)
      // The same file can be chosen again.
      if (input.current) input.current.value = ''
    }
  }

  const confirm = async () => {
    if (!plan) return
    setBusy(true)
    try {
      const { coverFailures } = await applyPlan(plan, store, { owner, storage, decode })
      const merged = plan.merged.length
      setDone(
        `Imported ${count(plan.added.length, 'new book', 'new books')}` +
          (merged > 0 ? ` and merged ${String(merged)}` : '') +
          '.' +
          (coverFailures > 0 ? ` ${count(coverFailures, 'photo', 'photos')} could not be added.` : ''),
      )
      setPlan(null)
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : "The import didn't finish.")
    } finally {
      setBusy(false)
    }
  }

  const exportFile = async (make: () => Promise<{ name: string; file: Blob; missingPhotos?: number }> | { name: string; file: Blob; missingPhotos?: number }) => {
    setError('')
    setDone('')
    setExporting(true)
    try {
      const { name, file, missingPhotos = 0 } = await make()
      download(name, file)
      if (missingPhotos > 0) setDone(`${count(missingPhotos, 'photo', 'photos')} could not be included.`)
    } catch {
      setError("The file couldn't be made. Try again.")
    } finally {
      setExporting(false)
    }
  }

  const photos = getPhoto ?? (owner && storage ? storagePhotos(owner, storage) : () => Promise.resolve(null))
  const extras = plan && [
    plan.quoteCount > 0 ? count(plan.quoteCount, 'quote', 'quotes') : '',
    plan.goals.length > 0 ? count(plan.goals.length, 'reading goal', 'reading goals') : '',
    plan.uploads.length > 0 ? count(plan.uploads.length, 'cover photo', 'cover photos') : '',
  ].filter((t) => t !== '')

  return (
    <main className="import-page">
      <h1>Import and export</h1>

      <section aria-labelledby="import-title">
        <h2 id="import-title">Import</h2>
        <p>Choose a Goodreads library export (.csv) or a MyBooks backup (.zip or .json). You will see what it does first.</p>
        <label>
          Goodreads or MyBooks file
          <input
            ref={input}
            type="file"
            accept=".csv,.zip,.json,text/csv,application/zip,application/json"
            disabled={busy}
            onChange={(e) => void choose(e.target.files?.[0])}
          />
        </label>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {plan && (
          <div className="import-preview">
            <p role="status">{previewText(plan)}</p>
            {extras && extras.length > 0 && <p>Also {extras.join(', ').replace(/, ([^,]*)$/, ' and $1')}.</p>}
            <button type="button" disabled={busy} onClick={() => void confirm()}>
              Import
            </button>{' '}
            <button type="button" disabled={busy} onClick={() => setPlan(null)}>
              Cancel
            </button>
          </div>
        )}
        {done && <p role="status">{done}</p>}
      </section>

      <section aria-labelledby="export-title">
        <h2 id="export-title">Export</h2>
        <p>
          <button type="button" disabled={exporting} onClick={() => void exportFile(() => goodreadsExport(store))}>
            Export for Goodreads
          </button>{' '}
          <button type="button" disabled={exporting} onClick={() => void exportFile(() => backupExport(store, now(), photos))}>
            Export a backup
          </button>
        </p>
        {exporting && <p role="status">Preparing the export…</p>}
        <p>The backup is the same file the phone makes, with your cover photos. Goodreads leaves out your private notes.</p>
      </section>
    </main>
  )
}
