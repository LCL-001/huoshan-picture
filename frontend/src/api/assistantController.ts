import { BASE_URL } from '@/request'

/** backend 代理端点（T10）：引擎的 SSE 事件流由后端中继，浏览器不直连引擎 */
const ASSISTANT_CHAT_PATH = '/api/ai/assistant/chat'

/**
 * 拼对话流地址（GET + SSE）。
 *
 * 用 EventSource 消费，所以地址走 query 而不是 body：浏览器原生 SSE 只支持 GET，
 * 凭据（satoken / Spring Session）由同站 Cookie 自动携带，前端不需要也无法自己设 header。
 */
export const buildAssistantChatUrl = (message: string, chatId?: string) => {
  const params = new URLSearchParams({ message })
  if (chatId) {
    params.set('chatId', chatId)
  }
  return `${BASE_URL}${ASSISTANT_CHAT_PATH}?${params.toString()}`
}
