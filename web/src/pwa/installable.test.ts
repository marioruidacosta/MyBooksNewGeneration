import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = process.cwd()
const BASE = import.meta.env.BASE_URL
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')

/** Width and height of a PNG, and whether it has an alpha channel (color type 4 or 6). */
function png(file: string) {
  const b = readFileSync(resolve(root, 'public', file))
  expect(b.subarray(1, 4).toString()).toBe('PNG')
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20), alpha: b[25] === 4 || b[25] === 6, bytes: b.length }
}

type Icon = { src: string; sizes: string; type: string; purpose?: string }

describe('installing on a phone', () => {
  it('theManifestIsValidAndUnderTheBase', () => {
    const m = JSON.parse(read('public/manifest.webmanifest')) as {
      name: string; short_name: string; start_url: string; scope: string; display: string; orientation?: string
      background_color: string; theme_color: string; icons: Icon[]
    }
    expect(BASE).toBe('/MyBooksNewGeneration/app/')
    expect(m.name).toBe('MyBooks')
    expect(m.short_name).toBe('MyBooks')
    expect(m.start_url).toBe(BASE)
    expect(m.scope).toBe(BASE)
    expect(m.display).toBe('standalone')
    expect(m.orientation).toBeUndefined() // portrait is not forced
    expect(m.background_color).toBe('#0f1c18')
    expect(m.theme_color).toBe('#0f1c18')

    // Icons are found next to the manifest (relative URLs), so they sit under the base too.
    const find = (sizes: string, purpose?: string) => m.icons.find((i) => i.sizes === sizes && i.purpose === purpose)
    for (const icon of [find('192x192'), find('512x512'), find('512x512', 'maskable')]) {
      expect(icon).toBeDefined()
      expect(icon!.type).toBe('image/png')
      expect(icon!.src.startsWith('/')).toBe(false)
      expect(existsSync(resolve(root, 'public', icon!.src))).toBe(true)
      const [w] = icon!.sizes.split('x').map(Number)
      expect(png(icon!.src)).toMatchObject({ width: w, height: w })
    }
    // Sensible sizes: a handful of KB, not the 1024 px original.
    expect(png(find('512x512')!.src).bytes).toBeLessThan(60_000)
  })

  it('thePageLinksTheIconsAndManifest', () => {
    const html = read('index.html')
    const link = (rel: string) => new RegExp(`<link[^>]*rel="${rel}"[^>]*>`).exec(html)?.[0] ?? ''
    // A root-relative address in index.html gets the base put in front of it by the build.
    expect(link('manifest')).toContain('href="/manifest.webmanifest"')
    expect(link('apple-touch-icon')).toContain('href="/apple-touch-icon.png"')
    expect(png('apple-touch-icon.png')).toMatchObject({ width: 180, height: 180, alpha: false })

    const meta = (name: string) => new RegExp(`<meta[^>]*name="${name}"[^>]*content="([^"]*)"`).exec(html)?.[1]
    expect(meta('apple-mobile-web-app-capable')).toBe('yes')
    expect(meta('mobile-web-app-capable')).toBe('yes')
    expect(meta('apple-mobile-web-app-title')).toBe('MyBooks')
    expect(meta('apple-mobile-web-app-status-bar-style')).toBe('black-translucent')
    expect(meta('theme-color')).toBe('#0f1c18')
    expect(/<meta[^>]*name="viewport"[^>]*content="[^"]*viewport-fit=cover/.test(html)).toBe(true)
    // The first-paint script sets the theme colour too, so Paper never flashes the Night one.
    expect(html).toMatch(/<script>[\s\S]*theme-color[\s\S]*<\/script>/)
  })
})
