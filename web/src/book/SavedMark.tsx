/**
 * The small "Saved" tick beside a label. The polite status region is always there (so it is announced when its text
 * appears) and keeps its place, so nothing jumps when the tick comes and goes.
 */
export function SavedMark({ saved }: { saved: boolean }) {
  return (
    <span className="saved" role="status">
      {saved && (
        <>
          <span aria-hidden="true">✓ </span>Saved
        </>
      )}
    </span>
  )
}
