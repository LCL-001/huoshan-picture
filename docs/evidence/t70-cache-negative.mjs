// From frontend: Get-Content -Raw -Encoding UTF8 ../docs/evidence/t70-cache-negative.mjs | node --input-type=module
// Expected exit 1 and 0/21 passed. Only in-memory SFC reads are replaced; working files are untouched.
import fs from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
const paths = ['frontend/src/components/PageTransition.vue', 'frontend/src/pages/SpaceDetailPage.vue']
const old = new Map(paths.map(p => [resolve('..', p), execFileSync('git', ['show', `a5fb645:${p}`], { encoding: 'utf8' })]))
const originalRead = fs.readFileSync
fs.readFileSync = function(file, ...args) {
  const path = file instanceof URL ? fileURLToPath(file) : typeof file === 'string' ? resolve(file) : ''
  return old.has(path) ? old.get(path) : originalRead.call(this, file, ...args)
}
await import('./scripts/check-private-page-cache.mjs')
