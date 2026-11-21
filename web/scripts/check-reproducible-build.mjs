// Two clean builds of the same code must give the same service worker and the same precache list, byte for byte.
// The site is rebuilt and published every 3 hours for the news; if the worker changed each time, every installed copy
// would offer "New version available" three times a day. Run: npm run check:reproducible (needs the VITE_SUPABASE_* settings).
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

const root = process.cwd()
const outs = ['a', 'b'].map((n) => join(root, 'node_modules', '.tmp', `repro-${n}`))
const vite = join(root, 'node_modules', 'vite', 'bin', 'vite.js')

function files(dir) {
  return readdirSync(dir, { recursive: true })
    .map((f) => String(f).split(sep).join('/'))
    .filter((f) => statSync(join(dir, f)).isFile())
    .sort()
}

for (const [i, out] of outs.entries()) {
  rmSync(out, { recursive: true, force: true })
  execFileSync(process.execPath, [vite, 'build', '--outDir', out, '--emptyOutDir'], { stdio: 'ignore' })
  // A pause between the builds, so anything that embeds the time shows up as a difference.
  if (i === 0) await new Promise((resolve) => setTimeout(resolve, 2500))
}

const [a, b] = outs.map(files)
const same = (x, y) => createHash('sha256').update(readFileSync(x)).digest('hex') === createHash('sha256').update(readFileSync(y)).digest('hex')
const problems = []
if (a.join('\n') !== b.join('\n')) problems.push(`different files: ${a.filter((f) => !b.includes(f)).concat(b.filter((f) => !a.includes(f))).join(', ')}`)
for (const f of a.filter((f) => b.includes(f))) if (!same(join(outs[0], f), join(outs[1], f))) problems.push(`differs: ${f}`)

const sw = readFileSync(join(outs[0], 'sw.js'), 'utf8')
const precached = [...sw.matchAll(/url:"([^"]+)"/g)].map((m) => m[1])
// What must and must not be in the precache list (sw.js is the one place that says).
for (const f of ['index.html', 'icon-192.png']) if (!precached.includes(f)) problems.push(`not precached: ${f}`)
for (const url of precached) {
  if (/\.map$|\.json$|supabase\.co/.test(url)) problems.push(`should not be precached: ${url}`)
  if (/literata-(cyrillic|greek|vietnamese)/.test(url)) problems.push(`font subset not needed: ${url}`)
}

for (const out of outs) rmSync(out, { recursive: true, force: true })
if (problems.length > 0) {
  console.error(`NOT reproducible or wrong precache:\n- ${problems.join('\n- ')}`)
  process.exit(1)
}
console.log(`Identical: ${a.length} files, sw.js ${relative(root, join(outs[0], 'sw.js'))} byte for byte, ${precached.length} precached.`)
