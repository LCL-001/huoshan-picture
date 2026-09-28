import myAxios from '@/request'

/** backend 代理端点（T36/T37）：会话索引与历史消息都只跟本服务说话 */
const ASSISTANT_CONVERSATIONS_PATH = '/api/ai/assistant/conversations'

/** 会话列表项（backend ai_conversation 的直出形态） */
export interface AssistantConversationItem {
  chatId: string
  title: string
  createTime: string
  updateTime: string
}

export const listAssistantConversations = async (page = 1, pageSize = 20): Promise<AssistantConversationItem[]> => {
  const response = await myAxios.get(ASSISTANT_CONVERSATIONS_PATH, { params: { page, pageSize } })
  return response.data?.data ?? []
}

/** 历史消息（引擎 ChatMessageVO"轮 + steps"结构，代理逐字透传） */
export const fetchAssistantConversationMessages = async (chatId: string) => {
  const response = await myAxios.get(`${ASSISTANT_CONVERSATIONS_PATH}/${chatId}/messages`)
  return response.data?.data ?? []
}
