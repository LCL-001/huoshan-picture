import myAxios, { BASE_URL } from '@/request'

/** backend 代理端点（T10）：引擎的 SSE 事件流由后端中继，浏览器不直连引擎 */
const ASSISTANT_CHAT_PATH = '/api/ai/assistant/chat'

/** 一次性凭据端点（R5）：每次建流前先取一张票 */
const ASSISTANT_TICKET_PATH = '/api/ai/assistant/ticket'

/** 写操作确认端点（T27.7 确认门）：卡片确认后原子消费待执行操作 */
const ASSISTANT_CONFIRM_PATH = '/api/ai/assistant/operations/confirm'

/** 写操作确认的执行结果（backend AiOperationResultVO 直出形态） */
export interface AssistantOperationResult {
  operationType: string
  success: boolean
  /** 计划涉及的条目数（batch_edit = 图片张数；url_import = URL 条数） */
  requestedCount?: number
  /** url_import：成功条数 */
  succeededCount?: number
  /** url_import：未成功条数（含因登录态失效未尝试的条目） */
  failedCount?: number
  /** url_import 逐条结果（batch_edit 为空列表） */
  items?: { ok: boolean; message?: string }[]
  /** 面向用户的补充说明 */
  message?: string
}

/**
 * 确认执行一个待确认写操作（T27.7）。
 *
 * 请求只携带 operationId + chatId + digest——三者都来自确认卡片上展示的内容，
 * **绝不携带任何业务 payload**：实际执行的是后端保存的原始计划，digest 钉死
 * "用户确认时看到的 = 实际执行的"。并发/重放由后端原子消费保证恰好一次。
 */
export const confirmAssistantOperation = async (
  operationId: string,
  chatId: string,
  digest: string,
): Promise<AssistantOperationResult> => {
  // timeout: 0（不限时）：confirm 在服务端**同步**执行（URL 入库逐条下载 + COS 转存，上限 20 条，
  // 单条就有 5s 连接 + 10s 读取预算），全站默认的 10s 会让客户端先超时、卡片谎报失败而后端仍在写入。
  // 确认是单次消费的幂等动作，等就完了——与对话流（EventSource 无客户端时限）同一取舍。
  const response = await myAxios.post(ASSISTANT_CONFIRM_PATH, { operationId, chatId, digest }, { timeout: 0 })
  const result = response.data?.data
  if (response.data?.code !== 0 || !result) {
    // 后端的失败文案都是用户安全口径（如"操作不存在、已过期或已处理"），原样上抛给卡片展示
    throw new Error(response.data?.message || '操作确认失败，请稍后重试')
  }
  return result
}

/**
 * 取一张一次性凭据（R5）。
 *
 * 跨站页面能借浏览器的 Cookie 打 chat（SameSite=Lax 挡的是子资源请求与跨站 POST，不挡顶层导航的 GET），
 * 但它读不到我们的响应体——票就是"你确实来自能读到我们响应的页面"的证明，攻击者凑不出这个参数。
 *
 * 走 axios 而不是 EventSource：签发是普通 JSON 请求，且登录态失效时 request.ts 的响应拦截器
 * 会接手提示并跳登录页（EventSource 读不到状态码，只能显示"连接中断"）。
 *
 * **必须判 `code`**：业务失败时 HTTP 仍可能是 200（如 Redis 不可用回 50000），
 * 直接取 `data.data` 会拿到 null ⇒ URL 变成 `ticket=null` ⇒ 用户看到"连接中断"而不是"没取得凭据"，
 * 还白打一次 chat（AGENTS.md 规则 17 那条"用户能看见的错误信息不可信"）。
 */
export const fetchAssistantTicket = async () => {
  const response = await myAxios.post(ASSISTANT_TICKET_PATH)
  const ticket = response.data?.data
  if (response.data?.code !== 0 || typeof ticket !== 'string' || !ticket) {
    throw new Error(response.data?.message || '未能取得本次对话凭据')
  }
  return ticket
}

/**
 * 拼对话流地址（GET + SSE）。
 *
 * 用 EventSource 消费，所以地址走 query 而不是 body：浏览器原生 SSE 只支持 GET，
 * 凭据（satoken / Spring Session）由同站 Cookie 自动携带，前端不需要也无法自己设 header。
 * ticket 同样只能放 query（原因相同）；它取用即失效，因此落进访问日志也无害。
 */
export const buildAssistantChatUrl = (message: string, chatId: string | undefined, ticket: string) => {
  const params = new URLSearchParams({ message, ticket })
  if (chatId) {
    params.set('chatId', chatId)
  }
  return `${BASE_URL}${ASSISTANT_CHAT_PATH}?${params.toString()}`
}
