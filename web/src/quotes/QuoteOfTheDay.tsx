import { useEffect, useMemo, useState } from 'react'
import { articleUrl, missPortrait, portraitUrl, quoteOfTheDay, type PortraitFetch } from './daily'

type Place = 'library' | 'sign-in'

type Props = {
  /** Where it sits: behind the library's header, or behind the sign-in form. */
  place?: Place
  /** The day to quote; today when left out. */
  now?: Date
  /** Brings the portrait; the browser's `fetch` when left out. */
  fetchImpl?: PortraitFetch
}

/** The width from which the portrait shows (theme.css uses the same two): below it only the quote line is shown. */
const PORTRAIT_FROM: Record<Place, string> = { library: '52rem', 'sign-in': '70rem' }

/** Whether the screen is wide enough for the portrait; true where the browser can't say. */
function useRoomForPortrait(place: Place): boolean {
  const query = `(min-width: ${PORTRAIT_FROM[place]})`
  const [room, setRoom] = useState(() => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : true))
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const list = window.matchMedia(query)
    const update = () => setRoom(list.matches)
    update()
    list.addEventListener('change', update)
    return () => list.removeEventListener('change', update)
  }, [query])
  return room
}

/**
 * The quote of the day, blended into the page: the author's portrait faded into the background (when it can be had)
 * and the quote set beside it. Decorative: it never blocks the page and shows the quote alone when the portrait fails.
 */
export function QuoteOfTheDay({ place = 'library', now, fetchImpl }: Props) {
  const day = useMemo(() => now ?? new Date(), [now])
  const quote = quoteOfTheDay(day)
  const room = useRoomForPortrait(place)
  const [portrait, setPortrait] = useState<{ wiki: string; url: string } | null>(null)

  useEffect(() => {
    if (!room) return
    const abort = new AbortController()
    void portraitUrl(quote.wiki, fetchImpl, Date.now(), abort.signal).then((url) => {
      if (!abort.signal.aborted && url !== null) setPortrait({ wiki: quote.wiki, url })
    })
    return () => abort.abort()
  }, [quote.wiki, fetchImpl, room])

  return (
    <figure className={`quote-day quote-day--${place}`}>
      {room && portrait?.wiki === quote.wiki && (
        <img
          className="quote-day__portrait"
          src={portrait.url}
          alt=""
          referrerPolicy="no-referrer"
          onError={() => {
            // Not asked for again for a day (the same miss as a failed lookup), not on every visit.
            missPortrait(quote.wiki)
            setPortrait(null)
          }}
        />
      )}
      <blockquote lang={quote.lang}>{quote.text}</blockquote>
      <figcaption>
        <a href={articleUrl(quote.wiki)} target="_blank" rel="noopener">
          {quote.author}
        </a>
        , <cite>{quote.work}</cite>
      </figcaption>
    </figure>
  )
}
