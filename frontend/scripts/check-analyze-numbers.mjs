#!/usr/bin/env node
// String-number conversion checks for analysis charts and the size column (AGENTS.md rule 12: the repro
// test stays forever).
//
// Context (T54 -> T63): the backend sends int64 fields (picSize, every per-dimension `count`)
// as STRINGS. Arithmetic and chart inputs must therefore convert EXPLICITLY -- `record.picSize / 1024`
// and `value: item.count` only worked because JS `/` and echarts coerce silently, and a missing value
// becomes NaN (a chart series or a "NaNKB" cell) instead of a defined 0.
//
// Two guards:
//   1. behavioural - toNumber() passes string numbers through, keeps a real 0 as 0 (the same trap as
//      formatSize: `if (!value) return fallback` would turn 0 into the fallback), and falls back on
//      missing/junk input;
//   2. structural - the four touched files route sizes/counts through the helpers, and NEVER wrap an id in
//      Number()/toNumber() (a 19-digit snowflake id loses precision as a double - decisions 2026-09-14 /
//      2026-10-13).
//
// The transpiled copy lives under node_modules/.cache/ and is CJS, for the same two reasons as
// check-format-size.mjs (bare specifiers resolve only inside the project tree; file-saver is UMD).
//
// Console output is ASCII only (rule 10: the Windows console is GBK and mangles Chinese).
// Run: cd frontend && npm run check:analyze-numbers
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const utilsPath = path.join(here, '..', 'src', 'utils', 'index.ts')
const componentPaths = [
  path.join(here, '..', 'src', 'pages', 'admin', 'PictureManagePage.vue'),
  path.join(here, '..', 'src', 'components', 'analyze', 'SpaceSizeAnalyze.vue'),
  path.join(here, '..', 'src', 'components', 'analyze', 'SpaceTagAnalyze.vue'),
  path.join(here, '..', 'src', 'components', 'analyze', 'SpaceUserAnalyze.vue'),
]

const { transform } = await import('esbuild')
const { code } = await transform(fs.readFileSync(utilsPath, 'utf8'), { loader: 'ts', format: 'cjs' })
const cacheDir = path.join(here, '..', 'node_modules', '.cache')
fs.mkdirSync(cacheDir, { recursive: true })
const tmp = path.join(cacheDir, `utils-index.numbers.${process.pid}.cjs`)
fs.writeFileSync(tmp, code, 'utf8')

let toNumber
try {
  const utils = await import(pathToFileURL(tmp).href)
  toNumber = utils.toNumber ?? utils.default?.toNumber
} finally {
  fs.rmSync(tmp, { force: true })
}

let failed = 0
const check = (name, actual, expected) => {
  if (actual !== expected) {
    failed++
    console.log(`FAIL ${name}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (name, condition, detail) => {
  if (!condition) {
    failed++
    console.log(`FAIL ${name}${detail ? '\n  ' + detail : ''}`)
  }
}

// ---------- 1. behavioural: toNumber ----------
checkTrue('toNumber is exported from src/utils/index.ts', typeof toNumber === 'function')
if (typeof toNumber === 'function') {
  // string numbers: what the API really sends for int64 fields
  check('"512" -> 512', toNumber('512'), 512)
  check('"0" -> 0', toNumber('0'), 0)
  check('"1048576" -> 1048576', toNumber('1048576'), 1048576)
  check('"1.5" -> 1.5', toNumber('1.5'), 1.5)
  check('number input passes through', toNumber(123), 123)
  check('negative number passes through', toNumber(-3), -3)
  // a real 0 is a value, not "missing" (the trap formatSize had before c977de3)
  check('0 -> 0, not the fallback', toNumber(0, -1), 0)
  // missing / junk -> fallback (defaults to 0)
  check('undefined -> 0', toNumber(undefined), 0)
  check('null -> 0', toNumber(null), 0)
  check('empty string -> 0', toNumber(''), 0)
  check('junk -> 0', toNumber('abc'), 0)
  check('NaN -> 0', toNumber(Number.NaN), 0)
  check('undefined honours a custom fallback', toNumber(undefined, -1), -1)
  check('junk honours a custom fallback', toNumber('abc', -1), -1)
  // why ids must never be routed through here: a numeric literal rounds exactly like Number(string), so
  // the only honest demonstration is the round-trip. Real snowflake id from the recycle-bin work (T58).
  const realId = '2111027283732324354'
  checkTrue(
    '19-digit ids do not survive a round trip through Number() (rationale: never call this on ids)',
    String(Number(realId)) !== realId,
    `Number('${realId}') -> '${String(Number(realId))}'`,
  )
}

// ---------- 2. structural: the four files must convert explicitly, and never convert an id ----------
const sources = new Map(componentPaths.map((p) => [path.basename(p), fs.readFileSync(p, 'utf8')]))

const sizePage = sources.get('PictureManagePage.vue') ?? ''
checkTrue(
  'PictureManagePage: no bare arithmetic on the string picSize',
  !/\(\s*record\.picSize\s*[/]/.test(sizePage),
  'picSize is a string (T54); use formatSize(record.picSize) instead of record.picSize / 1024',
)
checkTrue(
  'PictureManagePage: size goes through the unified formatSize',
  /formatSize\(\s*record\.picSize\s*\)/.test(sizePage),
  'expected formatSize(record.picSize)',
)

for (const name of ['SpaceSizeAnalyze.vue', 'SpaceTagAnalyze.vue', 'SpaceUserAnalyze.vue']) {
  checkTrue(
    `${name}: chart values convert count explicitly`,
    /toNumber\(\s*item\.count\s*\)/.test(sources.get(name) ?? ''),
    'expected toNumber(item.count) -- chart input must be a number, not the string the API sends',
  )
}

for (const [name, source] of sources) {
  checkTrue(
    `${name}: no id is converted to a number`,
    !/(?:Number|toNumber)\(\s*[\w.$]*id\b/i.test(source),
    'converting an id loses precision and breaks equality checks',
  )
}

if (failed > 0) {
  console.log(`\nRESULT: FAIL (${failed} case(s))`)
  process.exit(1)
}
console.log('RESULT: PASS (analyze numbers)')
