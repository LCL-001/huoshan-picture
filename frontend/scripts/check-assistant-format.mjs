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
const ssePath = path.join(here, '..', 'src', 'utils', 'assistantSse.ts')

const { transform } = await import('esbuild')
const transpile = async (srcPath) => {
  const { code } = await transform(fs.readFileSync(srcPath, 'utf8'), { loader: 'ts', format: 'esm' })
  const tmp = path.join(os.tmpdir(), `${path.basename(srcPath, '.ts')}.check.${process.pid}.mjs`)
  fs.writeFileSync(tmp, code, 'utf8')
  const loaded = await import(pathToFileURL(tmp).href)
  fs.rmSync(tmp, { force: true })
  return loaded
}
const format = await transpile(srcPath)
const sse = await transpile(ssePath)
const {
  parseAssistantText,
  summarizeToolResult,
  toolLabel,
  assembleHistoryTurns,
  assistantConfirmationTitle,
  describeAssistantOperationResult,
} = format
const { parseAssistantConfirmation } = sse

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
check('batchUploadByUrl has a label', toolLabel('batchUploadByUrl'), '\u6309 URL \u5bfc\u5165')
check('unknown tool falls back to its name', toolLabel('someNewTool'), 'someNewTool')
check('blank tool name', toolLabel(undefined), '未知工具')

// ---------- 3. tool summaries (payloads are the real ones from acceptance) ----------
const wrap = (name, json) => `工具 ${name} 完成了它的任务！结果：${json}`

// visionTagger real payload (2026-09-15 23:03, admin run)
check('visionTagger summary', summarizeToolResult(wrap('visionTagger',
  '{"spaceId":"2047963180567228418","requested":1,"suggested":1,"skipped":0,"suggestions":[{"pictureId":"2047963421827788801","url":"https://x/y.webp","ok":true,"tags":["创意","高清"],"category":"素材","message":null}],"note":"以上仅为建议，尚未写入图库。"}'),
  'visionTagger'), '建议 1 张（未写入图库）')
check('batch upload, all ok', summarizeToolResult(wrap('batchUploadByUrl',
  '{"spaceId":"1","requested":3,"succeeded":3,"failed":0,"skipped":0,"items":[]}'), 'batchUploadByUrl'), '\u5bfc\u5165 3 \u5f20')
check('batch upload, partial failure reported', summarizeToolResult(wrap('batchUploadByUrl',
  '{"spaceId":"1","requested":3,"succeeded":2,"failed":1,"skipped":0,"items":[]}'), 'batchUploadByUrl'), '\u5bfc\u5165 2 \u5f20\uff08\u5931\u8d25 1\uff09')
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

// ---------- 6. history turns (T38) ----------
const historyTurns = assembleHistoryTurns([
  { id: '0', role: 'assistant', content: '孤立回答', steps: null }, // 首个提问之前 → 跳过
  { id: '1', role: 'user', content: '列出我的空间', steps: null },
  { id: '2', role: 'assistant', content: '共 3 个空间',
    steps: [{ name: 'listSpaces', content: '{"total":3}' }] },
  { id: '3', role: 'assistant', content: '补充说明', steps: null }, // 同轮第二条：answer 覆盖、空 steps 不擦除
  { id: '4', role: 'user', content: '看看图片', steps: null },
  { id: '5', role: 'assistant', content: '共 5 张', steps: null },
])
check('history turn count', historyTurns.length, 2)
check('orphan assistant before first question is dropped', historyTurns[0].question, '列出我的空间')
check('last assistant text wins for answer', historyTurns[0].answer, '补充说明')
check('empty steps do not erase restored steps', historyTurns[0].steps,
  [{ kind: 'tool', name: 'listSpaces', content: '{"total":3}' }])
check('second turn built from its own pair',
  [historyTurns[1].question, historyTurns[1].answer], ['看看图片', '共 5 张'])
check('steps null maps to empty array', historyTurns[1].steps, [])

// ---------- T27.7 confirmation card (SSE frame parsing + card copy + result lines) ----------
check('confirmation frame parsed (fields verbatim)',
  parseAssistantConfirmation({
    event: 'confirmation_required',
    operationType: 'batch_edit',
    operationId: 'op-1',
    digest: 'd1',
    summary: ['target space 30', '2 pictures'],
  }),
  { operationType: 'batch_edit', operationId: 'op-1', digest: 'd1', summary: ['target space 30', '2 pictures'] })
checkTrue('confirmation without digest is rejected',
  parseAssistantConfirmation({ event: 'confirmation_required', operationId: 'op', summary: [] }) === null)
checkTrue('confirmation without operationId is rejected',
  parseAssistantConfirmation({ event: 'confirmation_required', digest: 'd', summary: [] }) === null)
checkTrue('non-confirmation event is rejected',
  parseAssistantConfirmation({ event: 'step', operationId: 'op', digest: 'd', summary: [] }) === null)
checkTrue('non-array summary is rejected',
  parseAssistantConfirmation({ event: 'confirmation_required', operationId: 'op', digest: 'd', summary: 'x' }) === null)

check('confirmation titles',
  [assistantConfirmationTitle('batch_edit'), assistantConfirmationTitle('url_import'), assistantConfirmationTitle('mystery')],
  ['\u6279\u91cf\u7f16\u8f91\u56fe\u7247', '\u6309 URL \u5bfc\u5165', '\u5199\u64cd\u4f5c'])

check('result lines: url_import full success',
  describeAssistantOperationResult({
    operationType: 'url_import', success: true, requestedCount: 2, succeededCount: 2, failedCount: 0,
  }),
  ['\u5bfc\u5165\u5b8c\u6210\uff1a\u6210\u529f 2 / 2 \u6761'])
check('result lines: url_import partial, reasons capped at 5',
  describeAssistantOperationResult({
    operationType: 'url_import', success: false, requestedCount: 8, succeededCount: 2, failedCount: 6,
    items: [3, 4, 5, 6, 7, 8].map((n) => ({ ok: false, message: `reason-${n}` })),
  }),
  ['\u5bfc\u5165\u672a\u5b8c\u5168\u6210\u529f\uff1a\u6210\u529f 2 \u6761\uff0c\u672a\u6210\u529f 6 \u6761',
    '\u672a\u6210\u529f\u539f\u56e0\uff1areason-3',
    '\u672a\u6210\u529f\u539f\u56e0\uff1areason-4',
    '\u672a\u6210\u529f\u539f\u56e0\uff1areason-5',
    '\u672a\u6210\u529f\u539f\u56e0\uff1areason-6',
    '\u672a\u6210\u529f\u539f\u56e0\uff1areason-7',
    '\u2026\u53e6\u6709 1 \u6761\u672a\u6210\u529f\u539f\u56e0\u7565'])
check('result lines: batch_edit submit-only semantics',
  describeAssistantOperationResult({ operationType: 'batch_edit', success: true, requestedCount: 3 }),
  ['\u6267\u884c\u5b8c\u6210\uff1a\u5df2\u63d0\u4ea4 3 \u5f20'])
check('result lines: backend message carried through',
  describeAssistantOperationResult({ operationType: 'batch_edit', success: true, requestedCount: 1, message: 'note-here' }),
  ['\u6267\u884c\u5b8c\u6210\uff1a\u5df2\u63d0\u4ea4 1 \u5f20', 'note-here'])

// ---------- T41: user-facing copy must not leak internal jargon ----------
// "入库" is internal vocabulary; the gallery's own copy says "导入" (UrlPictureUpload.vue: 粘贴图片直链，点击提交即可导入).
const JARGON = '\u5165\u5e93'
const userFacingCopy = [
  toolLabel('batchUploadByUrl'),
  toolLabel('batchEditPictures'),
  toolLabel('prepareUrlImportPictures'),
  toolLabel('prepareBatchEditPictures'),
  summarizeToolResult(wrap('batchUploadByUrl', '{"spaceId":"1","requested":3,"succeeded":1,"failed":2,"skipped":0,"items":[]}'), 'batchUploadByUrl'),
  assistantConfirmationTitle('url_import'),
  assistantConfirmationTitle('batch_edit'),
  ...describeAssistantOperationResult({ operationType: 'url_import', success: true, requestedCount: 2, succeededCount: 2, failedCount: 0 }),
  ...describeAssistantOperationResult({ operationType: 'url_import', success: false, requestedCount: 4, succeededCount: 1, failedCount: 3, items: [{ ok: false, message: 'x' }] }),
]
checkTrue('no internal jargon in user-facing copy',
  userFacingCopy.every((text) => !String(text).includes(JARGON)),
  JSON.stringify(userFacingCopy))
check('prepareUrlImportPictures label', toolLabel('prepareUrlImportPictures'),
  '\u751f\u6210 URL \u5bfc\u5165\u5f85\u786e\u8ba4\u64cd\u4f5c')

// ---------- T27.9 confirm-failure copy (transport failure: no English axios text, warn "may have run") ----------
// Optional call so a missing wiring shows up as a clean FAIL instead of a TypeError abort.
const confirmFailure = format.describeAssistantConfirmFailure
const TRANSPORT_NOTE =
  '\u786e\u8ba4\u8bf7\u6c42\u4e2d\u65ad\u6216\u8d85\u65f6\uff1a\u64cd\u4f5c\u53ef\u80fd\u5df2\u5728\u540e\u53f0\u6267\u884c\uff0c\u8bf7\u5148\u5230\u7a7a\u95f4\u91cc\u67e5\u770b\u7ed3\u679c\uff0c\u4e0d\u8981\u91cd\u590d\u63d0\u4ea4'
check('confirm failure: backend business message kept verbatim',
  confirmFailure?.(new Error('\u64cd\u4f5c\u4e0d\u5b58\u5728\u3001\u5df2\u8fc7\u671f\u6216\u5df2\u5904\u7406')),
  '\u786e\u8ba4\u672a\u5b8c\u6210\uff1a\u64cd\u4f5c\u4e0d\u5b58\u5728\u3001\u5df2\u8fc7\u671f\u6216\u5df2\u5904\u7406')
const axiosTimeout = Object.assign(new Error('timeout of 10000ms exceeded'),
  { isAxiosError: true, code: 'ECONNABORTED' })
const axiosNetwork = Object.assign(new Error('Network Error'), { isAxiosError: true, code: 'ERR_NETWORK' })
check('confirm failure: axios timeout mapped to the transport note', confirmFailure?.(axiosTimeout), TRANSPORT_NOTE)
check('confirm failure: axios network error mapped to the transport note', confirmFailure?.(axiosNetwork), TRANSPORT_NOTE)
check('confirm failure: non-Error falls back to the transport note', confirmFailure?.('boom'), TRANSPORT_NOTE)
checkTrue('confirm failure: no English axios text survives',
  [String(confirmFailure?.(axiosTimeout)), String(confirmFailure?.(axiosNetwork))]
    .every((text) => !/timeout of|Network Error/.test(text)),
  'raw axios text leaked into the card')

// ---------- T27.9: the confirm request must not inherit the global 10s axios timeout ----------
// Multi-URL imports run synchronously on the server (per-URL 5s connect + 10s read + COS, up to 20 URLs),
// so a 10s client timeout reports failure while the backend finishes writing.
const apiSrc = fs.readFileSync(path.join(here, '..', 'src', 'api', 'assistantController.ts'), 'utf8')
const confirmCallSrc = apiSrc.slice(apiSrc.indexOf('myAxios.post(ASSISTANT_CONFIRM_PATH'),
  apiSrc.indexOf('return result'))
checkTrue('confirm request overrides the global axios timeout',
  /timeout:\s*0/.test(confirmCallSrc),
  'confirmAssistantOperation must pass { timeout: 0 } (see T27.9)')

console.log(failed === 0 ? '\nRESULT: PASS (assistant format)' : `\nRESULT: ${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
