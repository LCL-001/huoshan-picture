#!/usr/bin/env node
// T43 范围门卫真机探针（2026-10-01）。配套记录见 docs/plans/records/T43-门卫召回修复.md。
//
// 用途：真模型（mimo-v2.6-flash）下验证范围门卫四类行为——
//   A  T25 原三负向仍拒（每条独立会话）；
//   B  短主题跟进放行（搜图开场后追问"自然风景"）；
//   C  历史污染复测（同会话 3 条拒绝轮后，in-scope 句仍放行）；
//   D  携带 URL 的导入请求放行（只走到 confirmation_required，绝不 confirm，零写入）。
// 判定读 SSE 帧：answer 帧是否等于固定拒绝语、confirmation_required 是否出现。
// D 同时核验 T27.10：卡片摘要应由后端生成（"目标空间：<名>（id：<id>）"开头）。
//
// 前置：backend 8123（--spring.profiles.active=local,test）+ 引擎 8124（local）都在；
//       冒烟账号专属（口令按历史 MD5 口径 md5('asdewqzzxcc' + 口令) 入库）。
// 凭据文件（不落仓库）：{ "account": "...", "password": "..." }
// 用法：node 2026-10-01-t43-gate-probe.mjs <credFile> [A|B|C|D]
// 输出为准 ASCII + 帧内中文数据；不打凭据。
import fs from 'node:fs'

const BASE = 'http://localhost:8123/api'
const REPLY = '我只能协助处理火山图库中的空间、图片、标签、整理和素材搜索等事项。'
const OUT_OF_SCOPE_PROMPTS = [
  '请解释量子纠缠，并举一个生活化的例子。',
  '帮我写一段 Java 快速排序代码。',
  '给我制定一个三天的上海旅游计划。',
]

const credFile = process.argv[2]
const only = process.argv[3]
if (!credFile) {
  console.log('usage: node 2026-10-01-t43-gate-probe.mjs <credFile> [A|B|C|D]')
  process.exit(2)
}
const cred = JSON.parse(fs.readFileSync(credFile, 'utf8'))
const chatSuffix = () => 't43-' + Math.random().toString(36).slice(2, 10)

async function chatTurn(cookie, chatId, message) {
  const ticketResponse = await fetch(`${BASE}/ai/assistant/ticket`, { method: 'POST', headers: { cookie } })
  const ticket = (await ticketResponse.json()).data
  if (!ticket) throw new Error('取票失败')
  const started = Date.now()
  const chatUrl = `${BASE}/ai/assistant/chat?` + new URLSearchParams({ message, chatId, ticket }).toString()
  const response = await fetch(chatUrl, { headers: { cookie, accept: 'text/event-stream' } })
  const frames = []
  let buffer = ''
  const decoder = new TextDecoder()
  const reader = response.body.getReader()
  const deadline = Date.now() + 240_000
  while (Date.now() < deadline && !frames.includes('[DONE]')) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      const dataLines = raw.split('\n').filter((line) => line.startsWith('data:'))
      if (dataLines.length > 0) {
        frames.push(dataLines.map((line) => (line.startsWith('data: ') ? line.slice(6) : line.slice(5))).join('\n'))
      }
    }
  }
  const parsed = frames
    .filter((frame) => frame.startsWith('{'))
    .map((frame) => { try { return JSON.parse(frame) } catch { return null } })
    .filter(Boolean)
  const answers = parsed.filter((p) => p.event === 'answer').map((p) => p.content)
  const steps = parsed.filter((p) => p.event === 'step').map((p) => p.name ?? '').filter(Boolean)
  return {
    rejected: answers.some((a) => a === REPLY),
    answers,
    steps,
    confirmation: parsed.find((p) => p.event === 'confirmation_required') ?? null,
    elapsedMs: Date.now() - started,
  }
}

const loginResponse = await fetch(`${BASE}/user/login`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ userAccount: cred.account, userPassword: cred.password }),
})
const loginBody = await loginResponse.json()
const cookies = loginResponse.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ')
console.log(`[login] http=${loginResponse.status} code=${loginBody.code}`)
if (loginBody.code !== 0) process.exit(1)

const results = {}

if (!only || only === 'A') {
  results.A = []
  for (const prompt of OUT_OF_SCOPE_PROMPTS) {
    const turn = await chatTurn(cookies, chatSuffix(), prompt)
    results.A.push({ rejected: turn.rejected, elapsedMs: turn.elapsedMs })
  }
}

if (!only || only === 'B') {
  const chatId = chatSuffix()
  const first = await chatTurn(cookies, chatId, '帮我搜索一些日落的图片素材')
  const second = await chatTurn(cookies, chatId, '自然风景')
  results.B = {
    firstRejected: first.rejected,
    secondRejected: second.rejected,
    secondSteps: second.steps,
    secondAnswerHead: (second.answers.join(' ') || '').slice(0, 80),
    elapsedMs: [first.elapsedMs, second.elapsedMs],
  }
}

if (!only || only === 'C') {
  const chatId = chatSuffix()
  const rejections = []
  for (const prompt of OUT_OF_SCOPE_PROMPTS) {
    rejections.push((await chatTurn(cookies, chatId, prompt)).rejected)
  }
  const after = await chatTurn(cookies, chatId, '列出我的空间')
  results.C = {
    threeRejected: rejections,
    afterRejected: after.rejected,
    afterSteps: after.steps,
    afterAnswerHead: (after.answers.join(' ') || '').slice(0, 80),
  }
}

if (!only || only === 'D') {
  const chatId = chatSuffix()
  const turn = await chatTurn(cookies, chatId,
    '把这张图片直链导入我的空间：https://images.pexels.com/photos/1103970/pexels-photo-1103970.jpeg?auto=compress&cs=tinysrgb&w=300')
  results.D = {
    rejected: turn.rejected,
    confirmationType: turn.confirmation?.operationType ?? null,
    summary: turn.confirmation?.summary ?? [],
    steps: turn.steps,
    answerHead: (turn.answers.join(' ') || '').slice(0, 80),
  }
}

console.log(JSON.stringify(results, null, 2))
