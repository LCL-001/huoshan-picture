#!/usr/bin/env node
// 文档一致性门禁（AGENTS.md 规则 17）——随 pre-commit 运行，docs-only 提交也跑。
//
// 治的是"同一事实有两份、只改了一份"这一类漂移：2026-09-15 复核发现，3 天里的 7 处勘误
// 全部源于此。这里只做**便宜且低误报**的机械核对，不做语义判断。
// 2026-09-18 扩展（文档保养）：预算（plan 行数 / decisions 决定列字数 / AGENTS 行数）、
// spec 三行纯度、handoff 份数、freshness 超期——防"文档随开发时长无界变长"。
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

// ---------- check 2b: the spec's 边界 (contract) line must reference real paths ----------
// The 边界 line is the user-confirmed contract for the app boundary (AGENTS.md rule 3 category ②).
// If a top-level app dir is renamed or removed, this line must be updated - catch it mechanically.
function checkContractBoundary() {
  const p = path.join(ROOT, 'docs/spec.md')
  if (!fs.existsSync(p)) return
  const line = fs.readFileSync(p, 'utf8').split(/\r?\n/).find((l) => l.startsWith('- **边界'))
  if (!line) { fail('docs/spec.md', 'the 边界 (boundary) bullet is missing from the 关键三行'); return }
  for (const m of line.matchAll(/`([^`]+)`/g)) {
    const token = m[1]
    let target = null
    if (token.endsWith('/')) target = token.slice(0, -1)
    else if (/^(backend|frontend|ai|docs)\/[\w./-]+$/.test(token)) target = token
    if (!target) continue
    if (!fs.existsSync(path.join(ROOT, target))) {
      fail('docs/spec.md', `边界 line references a path that does not exist: ${token}`)
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

// ---------- check 5: budgets (docs must not regrow; see "文档保养" in vibe-handoff) ----------
const BUDGET = { planLines: 120, decisionChars: 160, agentsLines: 150, handoffFiles: 10, freshnessDays: 45 }
const visibleLen = (s) => s
  .replace(/<!--.*?-->/g, '')
  .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  .replace(/[*_`]/g, '')
  .trim().length

function checkBudgets() {
  const planLines = fs.readFileSync(path.join(ROOT, 'docs/plan.md'), 'utf8').split(/\r?\n/).length
  if (planLines > BUDGET.planLines) {
    fail('docs/plan.md', `budget exceeded: ${planLines} lines > ${BUDGET.planLines} - archive closed milestones into docs/plans/archive/`)
  }
  const agentsLines = fs.readFileSync(path.join(ROOT, 'AGENTS.md'), 'utf8').split(/\r?\n/).length
  if (agentsLines > BUDGET.agentsLines) {
    console.log(`[docs-gate] WARN: AGENTS.md is ${agentsLines} lines > ${BUDGET.agentsLines} - move incident history to docs/workflow-history.md`)
  }
  const dl = fs.readFileSync(path.join(ROOT, 'docs/decisions.md'), 'utf8').split(/\r?\n/)
  for (let i = 0; i < dl.length; i++) {
    const l = dl[i].trim()
    if (!l.startsWith('|')) continue
    const cells = l.split('|').map((c) => c.trim())
    if (cells.length !== 6) continue
    if (/^:?-{2,}:?$/.test(cells[1]) || cells[1].includes('日期')) continue
    const len = visibleLen(cells[2])
    if (len > BUDGET.decisionChars) {
      fail('docs/decisions.md', `row ${i + 1}: decision cell ${len} chars > ${BUDGET.decisionChars} - keep one sentence and link the detail`)
    }
  }
}

// ---------- check 6: spec key-three-lines purity ----------
function checkSpecPurity() {
  const lines = fs.readFileSync(path.join(ROOT, 'docs/spec.md'), 'utf8').split(/\r?\n/)
  let inBlock = false
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i]
    if (/^##\s/.test(l)) {
      if (inBlock) break
      inBlock = /关键三行/.test(l)
      continue
    }
    if (!inBlock) continue
    if (/\d{4}-\d{1,2}-\d{1,2}/.test(l) || /已完成|已上线|已收口|全部完成|已部署/.test(l)) {
      if (allowed('docs/spec.md', l)) continue
      fail('docs/spec.md', `line ${i + 1}: key-three-lines must hold intent only (no dates, no progress) - status goes to plan.md, history to decisions.md`)
    }
  }
}

// ---------- check 7: handoff retention ----------
function checkHandoffRetention() {
  const dir = path.join(ROOT, 'docs/handoff')
  if (!fs.existsSync(dir)) return
  const snaps = fs.readdirSync(dir).filter((f) => f.endsWith('.md') && !f.startsWith('monthly-'))
  if (snaps.length > BUDGET.handoffFiles) {
    console.log(`[docs-gate] WARN: ${snaps.length} handoff snapshots > ${BUDGET.handoffFiles} - compress the oldest into monthly-YYYY-MM.md`)
  }
}

// ---------- check 8: freshness stamps ----------
function checkFreshness() {
  const now = Date.now()
  for (const file of ['AGENTS.md', 'docs/spec.md', 'docs/plan.md', 'docs/decisions.md']) {
    const p = path.join(ROOT, file)
    if (!fs.existsSync(p)) continue
    const head = fs.readFileSync(p, 'utf8').split(/\r?\n/).slice(0, 40).join('\n')
    const m = head.match(/freshness:[^\n]*reviewed:\s*['"]?(\d{4}-\d{2}-\d{2})/)
    if (!m) { console.log(`[docs-gate] WARN: ${file} has no freshness stamp`); continue }
    const age = Math.floor((now - Date.parse(m[1])) / 86400000)
    if (age > BUDGET.freshnessDays) console.log(`[docs-gate] WARN: ${file} freshness is ${age} days old - run docs grooming`)
  }
}

// ---------- run ----------
checkPaths()
checkContractBoundary()
checkCheckboxes()
checkPointers()
checkFrontendBaseline()
checkBudgets()
checkSpecPurity()
checkHandoffRetention()
checkFreshness()

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
