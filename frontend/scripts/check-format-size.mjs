#!/usr/bin/env node
// formatSize checks (AGENTS.md rule 12: the repro test stays forever).
//
// The frontend has no test framework, so we follow the check:batch-crawl-result approach: transpile the
// REAL src/utils/index.ts with esbuild and assert on it in node.
//
// Two differences from that script, both forced by this file's imports (dayjs / file-saver):
//   - the transpiled copy is written under node_modules/.cache/ (not os.tmpdir()): bare specifiers only
//     resolve from inside the project tree;
//   - it is transpiled to CJS: file-saver ships a UMD bundle whose named export is not detectable by
//     node's ESM-CJS interop ("Named export 'saveAs' not found" when imported as ESM).
//
// What these cases pin (T58):
//   - the backend sends int64 (picSize) as a STRING (T54: Long -> string), so the formatter must accept a
//     string;
//   - a 0-byte picture is KNOWN ("0 B"), not "未知" -- a naive `if (!size) return '未知'` turns the number
//     0 into "未知" (the bug fixed in c977de3 / T54);
//   - a real missing value (undefined / null / empty string) and a non-numeric string stay "未知".
//
// Console output is ASCII only (rule 10: the Windows console is GBK and mangles Chinese).
// Run: cd frontend && npm run check:format-size
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const srcPath = path.join(here, '..', 'src', 'utils', 'index.ts')

const { transform } = await import('esbuild')
const { code } = await transform(fs.readFileSync(srcPath, 'utf8'), { loader: 'ts', format: 'cjs' })
const cacheDir = path.join(here, '..', 'node_modules', '.cache')
fs.mkdirSync(cacheDir, { recursive: true })
const tmp = path.join(cacheDir, `utils-index.check.${process.pid}.cjs`)
fs.writeFileSync(tmp, code, 'utf8')

let formatSize
try {
  const utils = await import(pathToFileURL(tmp).href)
  formatSize = utils.formatSize ?? utils.default?.formatSize
} finally {
  fs.rmSync(tmp, { force: true })
}

if (typeof formatSize !== 'function') {
  console.log('FAIL could not load formatSize from src/utils/index.ts')
  process.exit(1)
}

let failed = 0
const check = (name, actual, expected) => {
  if (actual !== expected) {
    failed++
    console.log(`FAIL ${name}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`)
  }
}

// ---------- 1. zero bytes is a known size, both as a number and as the string the API sends ----------
check('0 (number) is not "unknown"', formatSize(0), '0 B')
check('"0" (string, as the API sends it) is not "unknown"', formatSize('0'), '0 B')

// ---------- 2. string input is parsed as bytes (T54: int64 arrives as a string) ----------
check('"512" renders bytes', formatSize('512'), '512 B')
check('"1024" crosses into KB', formatSize('1024'), '1.00 KB')
check('"1048576" crosses into MB', formatSize('1048576'), '1.00 MB')
check('number input still works', formatSize(1024), '1.00 KB')

// ---------- 3. real missing values (and junk) stay "unknown" ----------
check('undefined is unknown', formatSize(undefined), '未知')
check('null is unknown', formatSize(null), '未知')
check('empty string is unknown', formatSize(''), '未知')
check('non-numeric string is unknown', formatSize('abc'), '未知')

if (failed > 0) {
  console.log(`\nRESULT: FAIL (${failed} case(s))`)
  process.exit(1)
}
console.log('RESULT: PASS (format size)')
