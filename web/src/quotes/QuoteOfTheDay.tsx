import { useEffect, useMemo, useState } from 'react'
import { articleUrl, portraitUrl, quoteOfTheDay, type PortraitFetch } from './daily'

type Props = {
  /** Where it sits: behind the library's header, or behind the sign-in form. */
  place?: 'library' | 'sign-in'
  /** The day to quote; today when left out. */
  now?: Date
  /** Brings the portrait; the browser's `fetch` when left out. */
  fetchImpl?: PortraitFetch
}

/**
 * The quote of the day, blended into the page: the author's portrait faded into the background (when it can be had)
 * and the quote set beside it. Decorative: it never blocks the page and shows the quote alone when the portrait fails.
 */
export function QuoteOfTheDay({ place = 'library', now, fetchImpl }: Props) {
  const day = useMemo(() => now ?? new Date(), [now])
  const quote = quoteOfTheDay(day)
  const [portrait, setPortrait] = useState<{ wiki: string; url: string } | null>(null)

  useEffect(() => {
    let live = true
    void portraitUrl(quote.wiki, fetchImpl).then((url) => {
      if (live && url !== null) setPortrait({ wiki: quote.wiki, url })
    })
    return () => {
      live = false
    }
  }, [quote.wiki, fetchImpl])

  return (
    <figure className={`quote-day quote-day--${place}`}>
      {portrait?.wiki === quote.wiki && (
        <img className="quote-day__portrait" src={portrait.url} alt="" referrerPolicy="no-referrer" />
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
