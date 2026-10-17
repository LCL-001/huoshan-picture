// Reproduce from frontend: Get-Content -Raw -Encoding UTF8 ../docs/evidence/t70-build-no-delete.mjs | node --input-type=module
// Feed through stdin so existing frontend/node_modules resolves; do not run this file by path.
import fs from 'node:fs'
import { resolve, relative, join, dirname, basename } from 'node:path'
import { tmpdir } from 'node:os'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { syncBuiltinESMExports } from 'node:module'
import { transformSync } from 'esbuild'
import { build, mergeConfig } from 'vite'

const root = process.cwd()
const configPath = resolve('vite.config.ts')
const outDir = resolve(`node_modules/.cache/t70-build-20261017-${Date.now()}`)
if (fs.existsSync(outDir)) throw Error('Build output must be a new directory')

function snapshot(directory) {
  if (!fs.existsSync(directory)) return []
  return fs.readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const file = join(directory, entry.name)
      return entry.isDirectory() ? snapshot(file) : [[
        relative(root, file),
        createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
      ]]
    })
    .sort(([a], [b]) => a.localeCompare(b))
}
const previousDist = snapshot(resolve('dist'))
let source = fs.readFileSync(configPath, 'utf8')
source = source.replace(/from (['"])([^'"]+)\1/g,
  (_match, _quote, specifier) => `from ${JSON.stringify(import.meta.resolve(specifier))}`)
source = source.replace(/\bimport\.meta\.url\b/g, JSON.stringify(pathToFileURL(configPath).href))
const { code } = transformSync(source, { loader: 'ts', format: 'esm', target: 'es2022' })
const config = (await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)).default
let blockedDeletes = 0
let memoryTransportFallbacks = 0
const isEsbuildTemp = (file) => typeof file === 'string'
  && resolve(dirname(file)) === resolve(tmpdir()) && /^esbuild-[a-f0-9]{64}$/.test(basename(file))
const writeFile = fs.writeFile
fs.writeFile = function(file, ...args) {
  if (!isEsbuildTemp(file)) return writeFile.call(this, file, ...args)
  memoryTransportFallbacks++
  const callback = args.at(-1)
  queueMicrotask(() => callback(new Error('Use esbuild in-memory transform transport')))
}
const writeFileSync = fs.writeFileSync
fs.writeFileSync = function(file, ...args) {
  if (!isEsbuildTemp(file)) return writeFileSync.call(this, file, ...args)
  memoryTransportFallbacks++
  throw Error('Use esbuild in-memory transform transport')
}
for (const name of ['rm', 'rmSync', 'rmdir', 'rmdirSync', 'unlink', 'unlinkSync']) {
  fs[name] = (...args) => {
    blockedDeletes++
    console.error(`BLOCKED_DELETE fs.${name} ${String(args[0])}`)
    throw Error('Filesystem deletion forbidden')
  }
}
for (const name of ['rm', 'rmdir', 'unlink']) {
  fs.promises[name] = async (...args) => {
    blockedDeletes++
    console.error(`BLOCKED_DELETE fs.promises.${name} ${String(args[0])}`)
    throw Error('Filesystem deletion forbidden')
  }
}
syncBuiltinESMExports()
console.log(`BUILD_OUT_DIR=${outDir}`)
console.log('BUILD_OPTIONS=configFile:false mode:production emptyOutDir:false; esbuild temp transport disabled')
const result = await build(mergeConfig(config, {
  configFile: false, root, mode: 'production', build: { outDir, emptyOutDir: false },
}))
const outputs = (Array.isArray(result) ? result : [result]).flatMap((output) => output.output ?? [])
const currentDist = snapshot(resolve('dist'))
if (JSON.stringify(previousDist) !== JSON.stringify(currentDist)) throw Error('Existing dist was changed')
if (blockedDeletes) throw Error(`Deletion attempts detected: ${blockedDeletes}`)
console.log(`BUILD_RESULT chunks=${outputs.filter((file) => file.type === 'chunk').length} assets=${outputs.filter((file) => file.type === 'asset').length} total=${outputs.length}`)
console.log(`EXISTING_DIST_UNCHANGED=${previousDist.length} files`)
console.log(`ESBUILD_IN_MEMORY_FALLBACKS=${memoryTransportFallbacks}`)
console.log(`DELETE_ATTEMPTS=${blockedDeletes}`)
