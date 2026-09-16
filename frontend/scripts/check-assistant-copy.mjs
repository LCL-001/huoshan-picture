#!/usr/bin/env node
// User-facing assistant copy contract (T26).
// Keep output ASCII-only because the Windows console may use GBK.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const pagePath = path.join(here, '..', 'src', 'pages', 'AssistantPage.vue')
const source = fs.readFileSync(pagePath, 'utf8')

const expected = [
  '专注于火山图库：帮你查询空间和图片、整理标签、搜索并入库素材。只有你明确提出时才会修改数据，暂不支持删除。',
  '想从图库里做点什么？',
  '助手会按当前账号权限访问你的空间和图片，只处理与火山图库相关的事项。',
  '列出我的空间',
  '看看某个空间里有哪些图片',
  '帮我整理这些图片的标签',
  '搜索一些图片并放入我的空间',
  '输入与空间、图片、标签或搜图入库有关的问题…',
  'Enter 发送 · Shift+Enter 换行',
]

const removed = [
  '用你自己的登录态操作空间 / 图片 / 标签',
  '可以让助手帮你看看图库里的东西',
  '它调用的是你自己的登录态',
  '可读也可改：看图给建议',
  '问点什么…（Enter 发送，Shift+Enter 换行）',
]

let failed = 0
for (const text of expected) {
  if (!source.includes(text)) {
    failed++
    console.log(`FAIL missing expected copy: ${JSON.stringify(text)}`)
  }
}
for (const text of removed) {
  if (source.includes(text)) {
    failed++
    console.log(`FAIL stale copy remains: ${JSON.stringify(text)}`)
  }
}

const sampleMatch = source.match(/const samples = \[(.*?)\]/s)
const actualSamples = sampleMatch?.[1].match(/'([^']+)'/g)?.map((item) => item.slice(1, -1)) ?? []
const expectedSamples = expected.slice(3, 7)
if (JSON.stringify(actualSamples) !== JSON.stringify(expectedSamples)) {
  failed++
  console.log(`FAIL samples
  expected: ${JSON.stringify(expectedSamples)}
  actual:   ${JSON.stringify(actualSamples)}`)
}

console.log(failed === 0 ? '\nRESULT: PASS (assistant copy)' : `\nRESULT: ${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
