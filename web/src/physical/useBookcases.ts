import { useMemo, useSyncExternalStore } from 'react'
import type { LibraryStore } from '../sync/store'
import { BOOKCASE_PREFIX, MAX_BOOKCASE_NAME, parseBookcase, shelfIds, toStored, type Bookcase } from './model'
import { clearPlaces, storedBookcases } from './places'

/** The library's bookcases, kept current, and the ways to change them. Furniture never deletes a book. */
export function useBookcases(store: LibraryStore) {
  // A text, so the snapshot only changes when a bookcase does.
  const stored = useSyncExternalStore(store.subscribe, () => JSON.stringify(storedBookcases(store)))
  const bookcases = useMemo(
    () => (JSON.parse(stored) as { id: string; value: string }[]).flatMap(({ id, value }) => parseBookcase(BOOKCASE_PREFIX + id, value) ?? []),
    [stored],
  )
  return useMemo(() => {
    /** A bookcase from a newer version of the app is never written back: it would lose what this one can't read. */
    const save = (b: Bookcase) => {
      if (!b.readOnly) store.setPref(BOOKCASE_PREFIX + b.id, toStored(b))
    }
    return {
      bookcases,
      save,
      /** Every bookcase in that room moves to the new name; capitals don't matter in the old one. */
      renameRoom(from: string, to: string) {
        const name = to.trim().slice(0, MAX_BOOKCASE_NAME)
        if (name === '') return
        for (const b of bookcases) if (b.room.toLowerCase() === from.trim().toLowerCase()) save({ ...b, room: name })
      },
      /** Its books go to Without a place first. */
      remove(id: string) {
        const b = bookcases.find((x) => x.id === id)
        if (!b || b.readOnly) return
        clearPlaces(store, new Set(shelfIds(b)))
        store.setPref(BOOKCASE_PREFIX + id, null)
      },
    }
  }, [store, bookcases])
}
