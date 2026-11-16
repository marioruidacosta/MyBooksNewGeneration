import { coverColorCss } from './coverColor'

/** A cover drawn for a book without one: its colour, a darker spine, the title in serif and the author below. */
export function GeneratedCover({
  title, authors, compact = false, className = '',
}: { title: string; authors: string; compact?: boolean; className?: string }) {
  const author = authors.split(',')[0].trim()
  return (
    <span
      className={`generated-cover ${compact ? 'generated-cover--compact' : ''} ${className}`.trim()}
      style={{ backgroundColor: coverColorCss(title) }}
      {...(compact ? { 'aria-hidden': true } : { role: 'img', 'aria-label': `Cover of ${title}` })}
    >
      {!compact && (
        // The face sits inside the cover, so its sizes (in cqw) are taken from the cover's own width.
        <span className="generated-cover__face">
          <span className="generated-cover__title">{title}</span>
          {author !== '' && <span className="generated-cover__author">{author}</span>}
        </span>
      )}
    </span>
  )
}
