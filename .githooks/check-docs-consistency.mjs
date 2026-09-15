#!/usr/bin/env node
// 文档一致性门禁（AGENTS.md 规则 17）——随 pre-commit 运行，docs-only 提交也跑。
//
// 治的是"同一事实有两份、只改了一份"这一类漂移：2026-09-15 复核发现，3 天里的 7 处勘误
// 全部源于此。这里只做**便宜且低误报**的四项机械核对，不做语义判断。
//
// 红灯时的两种正确处置（不得绕过）：
//   1. 改文档，让它与事实一致；
//   2. 确属"历史陈述"（例如某文件已删、而记录里正当引用它），加进
//      .githooks/docs-consistency-allow.txt（每行一条 `相对路径::正则片段`），并写明理由。
// 输出一律 ASCII（Windows 控制台默认 GBK，中文常乱码，见规则 10）。

import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const ROOT = process.cwd()
const ALLOW_FILE = path.join(ROOT, '.githooks/docs-consistency-allow.txt')

/** 只核"活的"文档：历史快照（handoff/records/decisions 详情）按定义允许提到已删除的东西。 */
const LIVING = ['AGENTS.md', 'docs/spec.md', 'docs/plan.md', 'docs/decisions.md']
  .concat(fs.existsSync(path.join(ROOT, 'docs/features')) ? listMd('docs/features') : [])

function listMd(dir) {
  return fs.readdirSync(path.join(ROOT, dir)).filter((f) => f.endsWith('.md')).map((f) => `${dir}/${f}`)
}

const problems = []
const fail = (file, msg) => problems.push(`${file}: ${msg}`)

// ---------- allow-list ----------
const allow = []
if (fs.existsSync(ALLOW_FILE)) {
  for (const raw of fs.readFileSync(ALLOW_FILE, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const i = line.indexOf('::')
    if (i < 0) continue
    try { allow.push({ pathPrefix: line.slice(0, i), re: new RegExp(line.slice(i + 2)) }) } catch { /* ignore bad regex */ }
  }
}
const allowed = (file, text) => allow.some((a) => file.startsWith(a.pathPrefix) && a.re.test(text))

// ---------- check 1: referenced source paths must exist ----------
const PATH_RE = /`((?:backend|frontend|ai|docs)\/[\w./-]+\.(?:java|ts|vue|sql|yaml|yml|md|json|txt|sh|conf))`/g
const SRC_EXT = /\.(java|ts|vue|sql|yaml|yml|json|sh|conf)$/

function checkPaths() {
  for (const file of LIVING) {
    if (!fs.existsSync(path.join(ROOT, file))) continue
    const text = fs.readFileSync(path.join(ROOT, file), 'utf8')
    for (const line of text.split(/\r?\n/)) {
      for (const m of line.matchAll(PATH_RE)) {
        const rel = m[1]
        if (!SRC_EXT.test(rel)) continue // .md refs are often prose/plans
        if (rel.includes('...')) continue // `a/.../b.java` is an elision, not a path
        if (fs.existsSync(path.join(ROOT, rel))) continue
        if (allowed(file, line)) continue
        fail(file, `referenced path does not exist: ${rel}`)
      }
    }
  }
}

// ---------- check 2: plan.md checkbox vs body ----------
function checkCheckboxes() {
  const p = path.join(ROOT, 'docs/plan.md')
  if (!fs.existsSync(p)) return
  const items = []
  let cur = null
  for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^- \[([ x])\]\s*(.*)$/)
    if (m) { if (cur) items.push(cur); cur = { done: m[1] === 'x', title: m[2], body: [] } }
    else if (cur) cur.body.push(line)
  }
  if (cur) items.push(cur)
  for (const it of items) {
    const body = it.body.join('\n')
    if (it.done && body.includes('实施记录与证据')) continue
    // Only flag a substantial inline record (same threshold the 2026-09-15 migration used);
    // a one-line entry that happens to mention a commit hash is fine to keep inline.
    if (it.done && body.length > 600 && /提交 [0-9a-f]{7}|门禁.*\d+ 例|红绿|负向控制/.test(body) && !allowed('docs/plan.md', body)) {
      fail('docs/plan.md', `[${it.title.slice(0, 40)}] is checked done and carries a long inline record - move it to docs/plans/records/`)
    }
    if (!it.done && /实施记录与证据/.test(body) && !allowed('docs/plan.md', body)) {
      fail('docs/plan.md', `[${it.title.slice(0, 40)}] points to a record but is NOT checked - is it actually done?`)
    }
  }
}

// ---------- check 3: pointers resolve ----------
function checkPointers() {
  const p = path.join(ROOT, 'docs/plan.md')
  if (fs.existsSync(p)) {
    for (const m of fs.readFileSync(p, 'utf8').matchAll(/见 `docs\/([^`]+)`/g)) {
      if (!fs.existsSync(path.join(ROOT, 'docs', m[1]))) fail('docs/plan.md', `dangling pointer: docs/${m[1]}`)
    }
  }
  const d = path.join(ROOT, 'docs/decisions.md')
  if (fs.existsSync(d)) {
    for (const m of fs.readFileSync(d, 'utf8').matchAll(/\]\((decisions\/[^)]+)\)/g)) {
      if (!fs.existsSync(path.join(ROOT, 'docs', m[1]))) fail('docs/decisions.md', `dangling link: docs/${m[1]}`)
    }
  }
}

// ---------- check 4: frontend type-check baseline (only when frontend files are staged) ----------
function stagedFiles() {
  try {
    return execFileSync('git', ['diff', '--cached', '--name-only'], { cwd: ROOT, encoding: 'utf8' })
      .split(/\r?\n/).filter(Boolean)
  } catch { return [] }
}

function checkFrontendBaseline() {
  const staged = stagedFiles()
  const touchesFrontend = staged.some((f) => f.startsWith('frontend/'))
  const baseFile = path.join(ROOT, 'frontend/type-check-baseline.txt')
  if (!touchesFrontend || !fs.existsSync(baseFile)) {
    if (touchesFrontend && !fs.existsSync(baseFile)) fail('frontend', 'type-check-baseline.txt is missing (AGENTS.md rule 17)')
    return
  }
  if (!fs.existsSync(path.join(ROOT, 'frontend/node_modules'))) {
    console.log('[docs-gate] SKIP type-check baseline: frontend/node_modules absent')
    return
  }
  const baseline = Number((fs.readFileSync(baseFile, 'utf8').match(/^errors=(\d+)/m) || [])[1])
  let out = ''
  try {
    out = execFileSync('npm', ['run', 'type-check'], { cwd: path.join(ROOT, 'frontend'), encoding: 'utf8', shell: true })
  } catch (e) {
    out = `${e.stdout || ''}${e.stderr || ''}`
  }
  const actual = (out.match(/error TS/g) || []).length
  if (!Number.isFinite(baseline)) fail('frontend/type-check-baseline.txt', 'no `errors=<n>` line found')
  else if (actual > baseline) fail('frontend', `type-check errors ${actual} > baseline ${baseline} - fix them or justify and raise the baseline`)
  else console.log(`[docs-gate] type-check errors ${actual} <= baseline ${baseline} OK`)
}

// ---------- run ----------
checkPaths()
checkCheckboxes()
checkPointers()
checkFrontendBaseline()

if (problems.length === 0) {
  console.log('[docs-gate] PASS: docs consistency ok')
  process.exit(0)
}
console.log('')
console.log('[docs-gate] FAIL: docs are inconsistent with the repository:')
for (const p of problems) console.log('  - ' + p)
console.log('')
console.log('Fix the doc, or add an explicit allowance to .githooks/docs-consistency-allow.txt with a reason.')
process.exit(1)
