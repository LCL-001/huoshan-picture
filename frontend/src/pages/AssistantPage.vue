<template>
  <div id="assistantPage">
    <header class="page-header animate-fade-in-up">
      <h2 class="page-title">AI 助手</h2>
      <p class="page-subtitle">用你自己的登录态读取空间 / 图片 / 标签词表——看到的范围与你本人一致，当前只读不改</p>
    </header>

    <a-card class="assistant-card animate-fade-in-up stagger-dyn" style="--stagger-i: 1" :bordered="false">
      <template #title>
        <span class="assistant-card__title">对话</span>
        <a-tag v-if="loginUserStore.loginUser.userName" color="blue">
          {{ loginUserStore.loginUser.userName }}
        </a-tag>
      </template>
      <template #extra>
        <a-button size="small" :disabled="running" @click="startNewChat">新对话</a-button>
      </template>

      <div ref="listRef" class="assistant-list">
        <!-- 空状态：示例问题点一下即填入输入框 -->
        <div v-if="!turns.length" class="assistant-empty">
          <h3>可以让助手帮你看看图库里的东西</h3>
          <p>它调用的是你自己的登录态，看到的范围与你本人一致</p>
          <div class="assistant-empty__samples">
            <span v-for="sample in samples" :key="sample" @click="input = sample">{{ sample }}</span>
          </div>
          <p class="assistant-empty__hint">当前只能读取（空间 / 图片 / 标签词表），不会改动任何数据。</p>
        </div>

        <div v-for="(turn, index) in turns" :key="index" class="assistant-turn">
          <div class="assistant-bubble assistant-bubble--user">{{ turn.question }}</div>
          <StepTimeline :steps="turn.steps" :running="turn.running" />
          <div v-if="turn.answer" class="assistant-bubble assistant-bubble--assistant">
            <AssistantText :text="turn.answer" />
          </div>
        </div>
      </div>

      <div class="assistant-input">
        <a-textarea
          v-model:value="input"
          :auto-size="{ minRows: 2, maxRows: 4 }"
          :disabled="running"
          :placeholder="running ? '助手正在回答，请稍候…（可点“停止”中断）' : '问点什么…（Enter 发送，Shift+Enter 换行）'"
          @press-enter="onPressEnter"
        />
        <div class="assistant-input__actions">
          <a-button v-if="running" danger @click="stop">停止</a-button>
          <a-button v-else type="primary" :disabled="!input.trim()" @click="send">发送</a-button>
        </div>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { buildAssistantChatUrl, fetchAssistantTicket } from '@/api/assistantController.ts'
import { AssistantStream, type AssistantStep } from '@/utils/assistantSse.ts'
import AssistantText from '@/components/assistant/AssistantText.vue'
import StepTimeline from '@/components/assistant/StepTimeline.vue'

/** 一问一答（含这一轮的步骤），是本页唯一的会话结构 */
interface AssistantTurn {
  question: string
  answer: string
  steps: AssistantStep[]
  running: boolean
}

const CHAT_ID_KEY = 'assistant-chat-id'
const samples = ['我有几个空间？', '看看我的空间里都有什么图', '图库现在有哪些标签和分类？']

const router = useRouter()
const loginUserStore = useLoginUserStore()
const stream = new AssistantStream()

const turns = ref<AssistantTurn[]>([])
const input = ref('')
const listRef = ref<HTMLElement>()
const running = computed(() => turns.value.some((turn) => turn.running))

/** 对话串标识：同一 chatId 内助手记得上下文，"新对话"即换一个 */
let chatId = ''

const scrollToBottom = () => {
  nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

const onPressEnter = (event: KeyboardEvent) => {
  if (event.shiftKey) {
    return
  }
  event.preventDefault()
  send()
}

const send = async () => {
  const question = input.value.trim()
  if (!question || running.value) {
    return
  }
  turns.value.push({ question, answer: '', steps: [], running: true })
  // 必须取数组里的那个响应式对象：直接改 push 之前的原始对象不会触发视图更新
  const turn = turns.value[turns.value.length - 1]
  input.value = ''
  scrollToBottom()

  // R5：先取一次性凭据再建流。取票走 axios——登录态失效时 request.ts 的响应拦截器会提示并跳登录页，
  // 而 chat 本身是 EventSource，读不到状态码，只能显示"连接中断"。
  let ticket: string
  try {
    ticket = await fetchAssistantTicket()
  } catch {
    turn.running = false
    turn.answer = '助手暂时不可用（未能取得本次对话凭据），请稍后重试'
    scrollToBottom()
    return
  }

  stream.start(buildAssistantChatUrl(question, chatId, ticket), {
    onStep: (step) => {
      turn.steps.push(step)
      scrollToBottom()
    },
    onAnswer: (content) => {
      turn.answer = turn.answer ? `${turn.answer}\n${content}` : content
      scrollToBottom()
    },
    onError: (text) => {
      // 协议保证每条流以 [DONE] 收尾（T8-hard），走到这里就是真出错：
      // 已有回答时把原因追加在后面，避免用户只看到半截回答却不知道出了什么事
      turn.running = false
      turn.answer = turn.answer ? `${turn.answer}\n\n${text}` : text
      scrollToBottom()
    },
    onDone: () => {
      turn.running = false
    },
  })
}

const stop = () => {
  stream.stop()
  const current = turns.value.find((turn) => turn.running)
  if (current) {
    current.running = false
  }
}

const startNewChat = () => {
  stop()
  chatId = crypto.randomUUID()
  sessionStorage.setItem(CHAT_ID_KEY, chatId)
  turns.value = []
}

onMounted(() => {
  // 登录态必须在这里前置判：EventSource 拿不到 HTTP 状态码，等请求失败再提示就晚了
  if (!loginUserStore.loginUser.id) {
    router.replace(`/user/login?redirect=${encodeURIComponent('/assistant')}`)
    return
  }
  chatId = sessionStorage.getItem(CHAT_ID_KEY) ?? crypto.randomUUID()
  sessionStorage.setItem(CHAT_ID_KEY, chatId)
})

onUnmounted(() => stream.stop())
</script>

<style scoped>
#assistantPage {
  margin-bottom: 16px;
}

/* ---------- 页头 ---------- */

.page-header {
  margin-bottom: 8px;
}

.page-title {
  position: relative;
  margin: 0;
  padding-left: 14px;
  font-size: 22px;
  font-weight: 700;
}

.page-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 5px;
  height: 22px;
  border-radius: 3px;
  background: var(--app-primary);
}

.page-subtitle {
  margin: 6px 0 0 14px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

/* ---------- 对话卡片 ---------- */

.assistant-card__title {
  margin-right: 8px;
  font-size: 16px;
  font-weight: 600;
}

.assistant-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 56vh;
  min-height: 360px;
  overflow: auto;
  padding-right: 4px;
}

.assistant-turn {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.assistant-bubble {
  max-width: 82%;
  padding: 8px 12px;
  border-radius: var(--app-radius-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.assistant-bubble--user {
  align-self: flex-end;
  background: var(--app-primary-soft);
  border-top-right-radius: 2px;
}

.assistant-bubble--assistant {
  align-self: flex-start;
  border: 1px solid var(--app-border-soft);
  border-top-left-radius: 2px;
  background: var(--app-surface);
  /* 助手回答交给 AssistantText 按块渲染（Markdown 子集），不再原样保留换行 */
  white-space: normal;
}

/* ---------- 空状态 ---------- */

.assistant-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  text-align: center;
}

.assistant-empty h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.assistant-empty p {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--app-text-secondary);
}

.assistant-empty__samples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.assistant-empty__samples span {
  padding: 5px 11px;
  border: 1px dashed var(--app-border);
  border-radius: 999px;
  background: var(--app-primary-softer);
  color: var(--app-primary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.assistant-empty__samples span:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}

.assistant-empty__hint {
  margin: 18px 0 0;
  font-size: 12px;
}

/* ---------- 输入区 ---------- */

.assistant-input {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border-soft);
}

.assistant-input__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

@media (max-width: 768px) {
  .assistant-list {
    height: 60vh;
  }

  .assistant-bubble {
    max-width: 92%;
  }
}
</style>
