/** 折叠条里的一条步骤（对应引擎 SSE 的 step 事件） */
export interface AssistantStep {
  kind: 'think' | 'tool'
  name?: string
  content: string
}

export interface AssistantStreamHandlers {
  onStep: (step: AssistantStep) => void
  onAnswer: (content: string) => void
  onError: (messageText: string) => void
  onDone: () => void
}

/** 引擎事件协议里 data 帧的 JSON 形状（event 字段区分 step/answer/metrics） */
interface AssistantEventPayload {
  event?: string
  kind?: string
  name?: string
  content?: string
}

const DONE_FLAG = '[DONE]'

/**
 * AI 助手 SSE 客户端（消费 backend 代理端点的 GET 事件流）。
 *
 * 两条不能改的约束：
 * 1. 只能用 EventSource，不能复用 request.ts 的 axios 实例——浏览器端 axios 没有流式响应形态，
 *    且那里的 10s 超时会把长对话掐断；
 * 2. 收到 [DONE] 与出错时都必须显式 close()——EventSource 在流正常结束和出错时都会自动重连，
 *    不关会把同一条 message 重发一遍（等于重跑一次 agent、重复调工具）。
 *    R5 之后这条又多一层原因：一次性凭据取用即失效，自动重连带着已用过的票必然被服务端拒。
 */
export class AssistantStream {
  private source: EventSource | null = null

  start(url: string, handlers: AssistantStreamHandlers) {
    this.stop()
    const source = new EventSource(url, { withCredentials: true })
    this.source = source

    source.onmessage = (event: MessageEvent<string>) => {
      if (event.data === DONE_FLAG) {
        this.stop()
        handlers.onDone()
        return
      }
      const payload = parsePayload(event.data)
      if (!payload) {
        return
      }
      if (payload.event === 'step') {
        handlers.onStep({
          kind: payload.kind === 'tool' ? 'tool' : 'think',
          name: payload.name,
          content: payload.content ?? '',
        })
      } else if (payload.event === 'answer') {
        handlers.onAnswer(payload.content ?? '')
      }
      // metrics：图库助手当前不发（引擎侧未覆盖 runSummary），真发了也不渲染
    }

    source.onerror = () => {
      if (!this.source) {
        return
      }
      this.stop()
      // EventSource 拿不到 HTTP 状态码与错误响应体；服务端侧的失败会先以 answer 事件到达
      handlers.onError('助手连接中断，请重试')
    }
  }

  stop() {
    if (this.source) {
      this.source.close()
      this.source = null
    }
  }
}

function parsePayload(raw: string): AssistantEventPayload | null {
  try {
    return JSON.parse(raw) as AssistantEventPayload
  } catch {
    // 非 JSON 帧（引擎当前不会发）：跳过这一帧，不影响后续
    return null
  }
}
