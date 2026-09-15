import myAxios, { BASE_URL } from '@/request'

/** backend 代理端点（T10）：引擎的 SSE 事件流由后端中继，浏览器不直连引擎 */
const ASSISTANT_CHAT_PATH = '/api/ai/assistant/chat'

/** 一次性凭据端点（R5）：每次建流前先取一张票 */
const ASSISTANT_TICKET_PATH = '/api/ai/assistant/ticket'

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
