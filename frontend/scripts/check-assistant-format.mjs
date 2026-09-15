#!/usr/bin/env node
// Assistant presentation-layer pure-function checks (AGENTS.md rule 12: the repro test stays forever).
//
// The frontend has no test framework, so we follow the T11.1/T16 approach: transpile the REAL
// assistantFormat.ts with esbuild and assert on it in node. Unlike those two sessions the script is
// COMMITTED here - they left theirs in %TEMP% where it disappeared, which rule 16 (reproducible
// evidence) forbids.
//
// Cases come from the 2026-09-15 browser acceptance run, using the REAL payloads (including the answer
// that leaked a literal "## ..." heading and visionTagger's real tool result).
// Console output is ASCII only (rule 10: the Windows console is GBK and mangles Chinese).
// Run: cd frontend && npm run check:assistant-format
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const srcPath = path.join(here, '..', 'src', 'utils', 'assistantFormat.ts')

const { transform } = await import('esbuild')
const { code } = await transform(fs.readFileSync(srcPath, 'utf8'), { loader: 'ts', format: 'esm' })
const tmp = path.join(os.tmpdir(), `assistantFormat.check.${process.pid}.mjs`)
fs.writeFileSync(tmp, code, 'utf8')
const { parseAssistantText, summarizeToolResult, toolLabel } = await import(pathToFileURL(tmp).href)

let failed = 0
const check = (name, actual, expected) => {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    failed++
    console.log(`FAIL ${name}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (name, cond, detail) => {
  if (!cond) { failed++; console.log(`FAIL ${name}${detail ? '\n  ' + detail : ''}`) }
}

// ---------- 1. headings (the literal "##" found during acceptance) ----------
const mdAnswer = [
  '## 🏷️ 标签词表参考',
  '',
  '当前图库已有的标签包括 `热门`、`搞笑`。',
  '',
  '### 下一步',
  '- 要确认应用哪些图片？',
].join('\n')
const blocks = parseAssistantText(mdAnswer)
check('heading blocks detected (kind sequence)', blocks.map((b) => b.kind),
  ['heading', 'paragraph', 'heading', 'bullet'])
check('h2 maps to level 2', blocks[0].level, 2)
check('h3 maps to level 3', blocks[2].level, 3)
checkTrue('no literal hashes survive in parsed output',
  !blocks.some((b) => b.items.some((parts) => parts.some((p) => p.value.includes('##')))),
  'a heading part still carries "##"')
check('h1 and h6 levels', [parseAssistantText('# 一')[0].level, parseAssistantText('###### 六')[0].level], [1, 6])
check('mid-line hash is not a heading', parseAssistantText('标签 #1 已用')[0].kind, 'paragraph')
check('seven hashes is not a heading', parseAssistantText('####### 七')[0].kind, 'paragraph')

// ---------- 2. tool labels ----------
check('visionTagger has a label', toolLabel('visionTagger'), '看图打标建议')
check('batchUploadByUrl has a label', toolLabel('batchUploadByUrl'), '按 URL 入库')
check('unknown tool falls back to its name', toolLabel('someNewTool'), 'someNewTool')
check('blank tool name', toolLabel(undefined), '未知工具')

// ---------- 3. tool summaries (payloads are the real ones from acceptance) ----------
const wrap = (name, json) => `工具 ${name} 完成了它的任务！结果：${json}`

// visionTagger real payload (2026-09-15 23:03, admin run)
check('visionTagger summary', summarizeToolResult(wrap('visionTagger',
  '{"spaceId":"2047963180567228418","requested":1,"suggested":1,"skipped":0,"suggestions":[{"pictureId":"2047963421827788801","url":"https://x/y.webp","ok":true,"tags":["创意","高清"],"category":"素材","message":null}],"note":"以上仅为建议，尚未写入图库。"}'),
  'visionTagger'), '建议 1 张（未写入图库）')
check('batch upload, all ok', summarizeToolResult(wrap('batchUploadByUrl',
  '{"spaceId":"1","requested":3,"succeeded":3,"failed":0,"skipped":0,"items":[]}'), 'batchUploadByUrl'), '入库 3 张')
check('batch upload, partial failure reported', summarizeToolResult(wrap('batchUploadByUrl',
  '{"spaceId":"1","requested":3,"succeeded":2,"failed":1,"skipped":0,"items":[]}'), 'batchUploadByUrl'), '入库 2 张（失败 1）')
check('batch edit only claims submission', summarizeToolResult(wrap('batchEditPictures',
  '{"spaceId":"1","requestedCount":2,"appliedFields":["tags"],"success":true,"note":"n"}'), 'batchEditPictures'), '提交 2 张')
check('searchImage counts URLs (not JSON)', summarizeToolResult(
  '工具 searchImage 完成了它的任务！结果："https://a/1.jpeg,https://a/2.jpeg,https://a/3.jpeg"', 'searchImage'), '搜到 3 张图')
check('searchImage with no URL degrades', summarizeToolResult(
  '工具 searchImage 完成了它的任务！结果：（空）', 'searchImage'), '已完成')

// ---------- 4. existing behaviour must not regress ----------
check('list summary unchanged', summarizeToolResult(wrap('listSpaces', '{"total":37,"records":[]}'), 'listSpaces'), '共 37 个空间')
check('vocabulary summary unchanged', summarizeToolResult(wrap('getTagCategory', '{"tagList":["a"],"categoryList":[]}'), 'getTagCategory'), '标签 1 个 · 分类 0 个')
check('error payload still reported as failure', summarizeToolResult(wrap('listPictures', '{"error":true,"message":"无权限"}'), 'listPictures'), '失败：无权限')
check('non-JSON degrades', summarizeToolResult('工具 listPictures 完成了它的任务！结果：不是 JSON', 'listPictures'), '已完成')
check('unknown payload shape degrades', summarizeToolResult(wrap('listSpaces', '{"somethingElse":1}'), 'listSpaces'), '已完成')

// ---------- 5. XSS surface: parsing yields DATA only, never HTML ----------
const allowed = new Set(['text', 'strong', 'code', 'link'])
const xss = parseAssistantText('<script>alert(1)</script>\n\n**<img src=x onerror=1>**\n\n# <b>标题</b>')
checkTrue('no HTML fragments produced (data only; rendering is template interpolation)',
  xss.every((b) => b.items.every((parts) => parts.every((p) => allowed.has(p.type)))),
  JSON.stringify(xss))

fs.rmSync(tmp, { force: true })
console.log(failed === 0 ? '\nRESULT: PASS (assistant format)' : `\nRESULT: ${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
