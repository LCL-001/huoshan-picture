#!/usr/bin/env node
// 写确认门端到端探针（T27.9 真机验收用，2026-09-29）。见 docs/evidence/2026-09-29-confirm-gate-smoke.md。
//
// 用途：在不起浏览器的情况下走完 "登录 → 取票 → SSE 对话 → confirmation_required → confirm → 核对落库"，
// 并对 confirm 计时——这是 T27.9 那条 P2（前端 10s 超时会把长确认误报成失败）的真机判据。
//
// 前置：
//   1. backend 8123（--spring.profiles.active=local,test）、引擎 8124、MySQL/Redis 都在；
//   2. 有一个专属空间与对应的登录账号（口令按历史 MD5 口径 md5('asdewqzzxcc' + 口令) 入库）；
//   3. 凭据文件（不落仓库）：{ "account": "...", "password": "...", "userId": "...", "spaceId": "..." }
//
// 用法：node 2026-09-29-confirm-gate-probe.mjs <credFile> [urlCount]
// 输出为准 ASCII：只打状态/耗时/条数，不打凭据与完整 URL。
import fs from 'node:fs'

const BASE = 'http://localhost:8123/api'
const credFile = process.argv[2]
const urlCount = Number(process.argv[3] ?? 6)
if (!credFile) {
  console.log('usage: node 2026-09-29-confirm-gate-probe.mjs <credFile> [urlCount]')
  process.exit(2)
}

const cred = JSON.parse(fs.readFileSync(credFile, 'utf8'))
const chatId = 'probe-' + Date.now()
const urls = Array.from({ length: urlCount }, (_, i) => `https://picsum.photos/id/${1060 + i}/1600/1200.jpg`)

const loginResponse = await fetch(`${BASE}/user/login`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ userAccount: cred.account, userPassword: cred.password }),
})
const loginBody = await loginResponse.json()
const cookies = loginResponse.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ')
console.log(`[login] http=${loginResponse.status} code=${loginBody.code} cookies=${loginResponse.headers.getSetCookie().length}`)
if (loginBody.code !== 0) process.exit(1)

const ticketResponse = await fetch(`${BASE}/ai/assistant/ticket`, { method: 'POST', headers: { cookie: cookies } })
const ticket = (await ticketResponse.json()).data
console.log(`[ticket] http=${ticketResponse.status} ticketLen=${typeof ticket === 'string' ? ticket.length : 'n/a'}`)
if (!ticket) process.exit(1)

const message = [
  `把下面这 ${urls.length} 张图片直链原样导入我的空间（空间 id ${cred.spaceId}）：`,
  ...urls,
].join('\n')
const chatUrl = `${BASE}/ai/assistant/chat?` + new URLSearchParams({ message, chatId, ticket }).toString()
const chatStarted = Date.now()
const chatResponse = await fetch(chatUrl, { headers: { cookie: cookies, accept: 'text/event-stream' } })
console.log(`[chat] http=${chatResponse.status} type=${chatResponse.headers.get('content-type')}`)

const frames = []
let buffer = ''
const decoder = new TextDecoder()
const reader = chatResponse.body.getReader()
const deadline = Date.now() + 300_000
while (Date.now() < deadline && !frames.includes('[DONE]')) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  let index
  while ((index = buffer.indexOf('\n\n')) >= 0) {
    const rawFrame = buffer.slice(0, index)
    buffer = buffer.slice(index + 2)
    const dataLines = rawFrame.split('\n').filter((line) => line.startsWith('data:'))
    if (dataLines.length > 0) {
      frames.push(dataLines.map((line) => (line.startsWith('data: ') ? line.slice(6) : line.slice(5))).join('\n'))
    }
  }
}
console.log(`[chat] frames=${frames.length} elapsedMs=${Date.now() - chatStarted}`)

let confirmation = null
for (const frame of frames) {
  if (!frame.startsWith('{')) continue
  try {
    const parsed = JSON.parse(frame)
    if (parsed.event === 'confirmation_required') confirmation = parsed
  } catch { /* 非 JSON 帧忽略 */ }
}
if (!confirmation) {
  console.log('NO-CONFIRMATION-FRAME (model produced no plan, or the scope guard rejected this message - just retry)')
  process.exit(3)
}
console.log(`[confirmation] type=${confirmation.operationType} opIdLen=${String(confirmation.operationId).length} digestLen=${String(confirmation.digest).length}`)
console.log('note: summary lines below are server-provided payload text, printed as data:')
for (const line of confirmation.summary ?? []) console.log('  summary: ' + line)

const confirmStarted = Date.now()
const confirmResponse = await fetch(`${BASE}/ai/assistant/operations/confirm`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', cookie: cookies },
  body: JSON.stringify({ operationId: confirmation.operationId, chatId, digest: confirmation.digest }),
})
const elapsed = Date.now() - confirmStarted
const confirmBody = await confirmResponse.json()
console.log(`[confirm] http=${confirmResponse.status} code=${confirmBody.code} elapsedMs=${elapsed}`)
console.log(`[confirm] success=${confirmBody.data?.success} requested=${confirmBody.data?.requestedCount} succeeded=${confirmBody.data?.succeededCount} failed=${confirmBody.data?.failedCount}`)
console.log(elapsed > 10_000
  ? `VERDICT: confirm took ${elapsed}ms > 10000ms - the old 10s frontend timeout would have misreported this`
  : `VERDICT: confirm took ${elapsed}ms (under 10s)`)

const listResponse = await fetch(`${BASE}/picture/list/page/vo`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', cookie: cookies },
  body: JSON.stringify({ spaceId: cred.spaceId, current: 1, pageSize: 50 }),
})
const listBody = await listResponse.json()
console.log(`[verify] pictures in space=${listBody.data?.total}`)
