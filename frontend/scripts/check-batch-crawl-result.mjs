#!/usr/bin/env node
// Batch-crawl result mapping checks (AGENTS.md rule 12: the repro test stays forever).
//
// The frontend has no test framework, so we follow the check:assistant-format approach: transpile the
// REAL src/utils/batchCrawlResult.ts with esbuild and assert on it in node.
//
// The bug these cases pin (T30): the backend answers a successful crawl that stored 0 pictures with
// {code: 0, data: 0, message: "ok"}. The page tested `code === 0 && data`, and `0` is falsy in JS, so
// the user saw "task failed / ok". Since T29 (source-url dedupe) re-running the same search returns 0
// by design, that path became the most likely outcome instead of a rare one.
//
// Console output is ASCII only (rule 10: the Windows console is GBK and mangles Chinese).
// Run: cd frontend && npm run check:batch-crawl-result
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const srcPath = path.join(here, '..', 'src', 'utils', 'batchCrawlResult.ts')

const { transform } = await import('esbuild')
const { code } = await transform(fs.readFileSync(srcPath, 'utf8'), { loader: 'ts', format: 'esm' })
const tmp = path.join(os.tmpdir(), `batchCrawlResult.check.${process.pid}.mjs`)
fs.writeFileSync(tmp, code, 'utf8')
const { mapBatchCrawlResponse } = await import(pathToFileURL(tmp).href)

let failed = 0
const check = (name, actual, expected) => {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    failed++
    console.log(`FAIL ${name}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (name, cond, detail) => {
  if (!cond) {
    failed++
    console.log(`FAIL ${name}${detail === undefined ? '' : '\n  detail: ' + detail}`)
  }
}

// ---------- 1. the T30 regression: a successful run that stored nothing ----------
const empty = mapBatchCrawlResponse({ code: 0, data: 0, message: 'ok' })
check('zero pictures is not an error', empty.status, 'empty')
checkTrue('zero pictures keeps the completion title', empty.title === '任务执行完成', empty.title)
checkTrue('zero pictures explains itself instead of leaking the raw message',
  empty.desc.includes('没有新增') && !empty.desc.includes('ok'), empty.status)
checkTrue('zero pictures never says the task failed', !empty.desc.includes('失败'))

// ---------- 2. success path with real numbers ----------
const one = mapBatchCrawlResponse({ code: 0, data: 1, message: 'ok' })
check('one picture is a success', one.status, 'success')
checkTrue('one picture reports the count', one.desc.includes('1'), one.status)
const max = mapBatchCrawlResponse({ code: 0, data: 30, message: 'ok' })
check('upper bound (30) is a success', max.status, 'success')
checkTrue('upper bound reports 30', max.desc.includes('30'), max.status)

// ---------- 3. success without a number degrades to "nothing new", not to failure ----------
const missing = mapBatchCrawlResponse({ code: 0 })
check('success with no count is empty, not error', missing.status, 'empty')
const nullish = mapBatchCrawlResponse({ code: 0, data: undefined, message: 'ok' })
check('success with undefined data is empty', nullish.status, 'empty')

// ---------- 4. business errors keep the backend reason ----------
const denied = mapBatchCrawlResponse({ code: 40100, message: '未登录' })
check('business error stays an error', denied.status, 'error')
checkTrue('business error keeps the backend message', denied.desc === '未登录', denied.status)
const noMessage = mapBatchCrawlResponse({ code: 50000 })
check('error without a message falls back', noMessage.desc, '请稍后重试')
checkTrue('error title is unchanged', noMessage.title === '任务执行失败', noMessage.title)

// ---------- 5. statuses stay inside the union the page can render ----------
const statuses = new Set(['success', 'empty', 'error'])
checkTrue('every status is renderable by the page',
  [empty, one, missing, denied].every((r) => statuses.has(r.status)))

fs.rmSync(tmp, { force: true })
console.log(failed === 0 ? '\nRESULT: PASS (batch crawl result)' : `\nRESULT: ${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
