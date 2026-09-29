import type { AssistantTurn } from './assistantSse'
import type { AssistantOperationResult } from '@/api/assistantController.ts'

/** 引擎 step 事件里工具步骤的固定套语（BaseAgent：`工具 X 完成了它的任务！结果：<payload>`） */
const TOOL_RESULT_MARK = '结果：'

/** 工具名 → 给人看的说法；表里没有就退回工具名本身 */
const TOOL_LABELS: Record<string, string> = {
  listSpaces: '查询空间列表',
  listPictures: '查询图片清单',
  getTagCategory: '读取标签词表',
  visionTagger: '看图打标建议',
  batchEditPictures: '批量修改图片',
  batchUploadByUrl: '按 URL 导入',
  prepareBatchEditPictures: '生成批量修改待确认操作',
  prepareUrlImportPictures: '生成 URL 导入待确认操作',
  searchImage: '联网搜图',
}

/** 计数摘要的量词（按工具名分）；认不出就"条" */
const COUNT_UNITS: Record<string, string> = {
  listSpaces: '个空间',
  listPictures: '张图片',
}

export const toolLabel = (name?: string) => (name ? (TOOL_LABELS[name] ?? name) : '未知工具')

/**
 * 工具步骤的一句话摘要。
 *
 * 普通用户不需要看到原始 JSON 与参数（图片 URL、雪花 id 这类噪音），只需要"干了什么、结果几条"：
 * 原始返回另行收进「详情」。任何解析失败都退化成"已完成"——摘要永远不该抛错或空着。
 */
export const summarizeToolResult = (content: string, name?: string): string => {
  // MCP 搜图返回的是逗号拼接的 URL 串（不是 JSON），单独认一下，别退化成"已完成"
  if (name === 'searchImage') {
    const count = (content.match(/https?:\/\//g) ?? []).length
    return count > 0 ? `搜到 ${count} 张图` : '已完成'
  }
  const payload = parsePayloadObject(content)
  if (!payload) {
    return '已完成'
  }
  if (payload.error === true) {
    const reason = typeof payload.message === 'string' ? payload.message : '未知原因'
    return `失败：${reason}`
  }
  // prepare 工具的成功返回（T27.6 确认门）：计划已提交待确认，如实说明"还没改动任何数据"
  if (payload.status === 'pending_confirmation') {
    return '已生成待确认操作（未改动数据）'
  }
  // 打标只给建议、不落库，摘要里必须如实说清（与工具自己的 note 同口径）
  if (typeof payload.suggested === 'number') {
    return `建议 ${payload.suggested} 张（未写入图库）`
  }
  if (typeof payload.succeeded === 'number') {
    const failed = typeof payload.failed === 'number' ? payload.failed : 0
    return failed > 0 ? `导入 ${payload.succeeded} 张（失败 ${failed}）` : `导入 ${payload.succeeded} 张`
  }
  // 批编只代表"提交成功"，工具自己也只承诺到这一步（不属于该空间的 id 会被静默跳过）
  if (typeof payload.requestedCount === 'number') {
    return `提交 ${payload.requestedCount} 张`
  }
  if (typeof payload.total === 'number') {
    return `共 ${payload.total} ${COUNT_UNITS[name ?? ''] ?? '条'}`
  }
  const tags = Array.isArray(payload.tagList) ? payload.tagList.length : null
  const categories = Array.isArray(payload.categoryList) ? payload.categoryList.length : null
  if (tags !== null || categories !== null) {
    return `标签 ${tags ?? 0} 个 · 分类 ${categories ?? 0} 个`
  }
  return '已完成'
}

/** 取出工具返回里的 JSON 对象；不是 JSON（引擎改口径 / 模型改写）返回 null */
const parsePayloadObject = (content: string): Record<string, unknown> | null => {
  const markIndex = content.lastIndexOf(TOOL_RESULT_MARK)
  const candidate = markIndex >= 0 ? content.slice(markIndex + TOOL_RESULT_MARK.length) : content
  const start = candidate.indexOf('{')
  const end = candidate.lastIndexOf('}')
  if (start < 0 || end <= start) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(candidate.slice(start, end + 1))
    return parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

export type InlinePart =
  | { type: 'text'; value: string }
  | { type: 'strong'; value: string }
  | { type: 'code'; value: string }
  | { type: 'link'; value: string; href: string }

export type TextBlock = {
  kind: 'paragraph' | 'bullet' | 'ordered' | 'quote' | 'heading'
  /** 仅 heading 用：模型的 # 级数（1-6），渲染时映射到 h3~h6 */
  level?: number
  items: InlinePart[][]
}

/**
 * 只认模型实际会用的那几样：`**粗体**`、`` `行内代码` ``、http(s) 链接、`- `/`* ` 与 `1. ` 列表、`> ` 引用、`#`~`######` 标题。
 *
 * 不做表格/嵌套/图片，也**不产出 HTML**——渲染交回 Vue 模板插值（自动转义），
 * 从根上不留 XSS 面（回答内容会被工具数据与用户提问影响，绝不能当 HTML 渲染）。
 * 连续的非列表行各自成段：模型本来就按句成行，不再做合并猜测。
 */
export const parseAssistantText = (text: string): TextBlock[] => {
  const blocks: TextBlock[] = []
  let current: TextBlock | null = null
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) {
      current = null
      continue
    }
    // 标题：模型常写 `## 标题`，此前不认它、井号会原样透出（2026-09-15 验收实测）
    const headingMatch = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (headingMatch) {
      current = null
      blocks.push({ kind: 'heading', level: headingMatch[1].length, items: [parseInline(headingMatch[2])] })
      continue
    }
    const listMatch = /^(?:[-*]|(\d+)[.)])\s+(.*)$/.exec(trimmed)
    if (listMatch) {
      const kind = listMatch[1] ? 'ordered' : 'bullet'
      if (!current || current.kind !== kind) {
        current = { kind, items: [] }
        blocks.push(current)
      }
      current.items.push(parseInline(listMatch[2]))
      continue
    }
    const quoteMatch = /^>\s?(.*)$/.exec(trimmed)
    if (quoteMatch) {
      if (!current || current.kind !== 'quote') {
        current = { kind: 'quote', items: [] }
        blocks.push(current)
      }
      current.items.push(parseInline(quoteMatch[1]))
      continue
    }
    current = null
    blocks.push({ kind: 'paragraph', items: [parseInline(trimmed)] })
  }
  return blocks
}

/** 行内三件套：`代码` 优先于 **粗体**，URL 只在 http(s) 下成链 */
const parseInline = (line: string): InlinePart[] => {
  const pattern = /`([^`]+)`|\*\*([^*]+)\*\*|(https?:\/\/[^\s<>()（）,，。、；：！？"']+)/g
  const parts: InlinePart[] = []
  let cursor = 0
  let match = pattern.exec(line)
  while (match) {
    if (match.index > cursor) {
      parts.push({ type: 'text', value: line.slice(cursor, match.index) })
    }
    if (match[1] !== undefined) {
      parts.push({ type: 'code', value: match[1] })
    } else if (match[2] !== undefined) {
      parts.push({ type: 'strong', value: match[2] })
    } else if (match[3] !== undefined) {
      parts.push({ type: 'link', value: match[3], href: match[3] })
    }
    cursor = match.index + match[0].length
    match = pattern.exec(line)
  }
  if (cursor < line.length) {
    parts.push({ type: 'text', value: line.slice(cursor) })
  }
  return parts.length > 0 ? parts : [{ type: 'text', value: line }]
}

/** 待确认操作类型 → 卡片标题；认不出就退回通用说法（不暴露内部类型名） */
export const assistantConfirmationTitle = (operationType: string): string => {
  if (operationType === 'batch_edit') {
    return '批量编辑图片'
  }
  if (operationType === 'url_import') {
    return '按 URL 导入'
  }
  return '写操作'
}

/**
 * 确认执行结果 → 卡片上的结果行（T27.7）。
 * 如实呈现"提交了几张 / 成功几条"：batch_edit 只代表提交成功（图库按空间与图片过滤，
 * 不逐张确认），url_import 逐条上报；失败原因取后端的用户安全文案，最多列 5 条。
 */
export const describeAssistantOperationResult = (result: AssistantOperationResult): string[] => {
  const requested = typeof result.requestedCount === 'number' ? result.requestedCount : null
  const lines: string[] = []
  if (result.operationType === 'url_import') {
    const succeeded = typeof result.succeededCount === 'number' ? result.succeededCount : 0
    const failed = typeof result.failedCount === 'number' ? result.failedCount : 0
    lines.push(
      result.success
        ? `导入完成：成功 ${succeeded} / ${requested ?? succeeded + failed} 条`
        : `导入未完全成功：成功 ${succeeded} 条，未成功 ${failed} 条`,
    )
  } else if (requested !== null) {
    lines.push(result.success ? `执行完成：已提交 ${requested} 张` : '执行未完成')
  } else {
    lines.push(result.success ? '执行完成' : '执行未完成')
  }
  if (result.message) {
    lines.push(result.message)
  }
  const failures = (result.items ?? []).filter((item) => !item.ok && item.message)
  for (const item of failures.slice(0, 5)) {
    lines.push(`未成功原因：${item.message}`)
  }
  if (failures.length > 5) {
    lines.push(`…另有 ${failures.length - 5} 条未成功原因略`)
  }
  return lines
}

/** axios 的传输失败（超时/网络中断）与后端业务拒绝的区分标记：只有 axios 错误对象带这个字段 */
const isTransportFailure = (error: unknown): boolean =>
  typeof error === 'object' && error !== null && (error as { isAxiosError?: unknown }).isAxiosError === true

/**
 * 确认执行失败 → 卡片上的失败行（T27.9）。
 *
 * 业务拒绝（后端用户安全文案，如"操作不存在、已过期或已处理"）原样展示；
 * 传输失败（超时/网络中断）与未知错误统一成一条固定提示——confirm 是**服务端同步执行**的动作
 * （多图入库逐条下载转存，可能仍在后台跑），客户端等不到响应不代表没执行。
 * 无论如何都不把 axios 的英文原文（timeout of 10000ms exceeded）当用户文案。
 */
export const describeAssistantConfirmFailure = (error: unknown): string => {
  if (error instanceof Error && error.message && !isTransportFailure(error)) {
    return `确认未完成：${error.message}`
  }
  return '确认请求中断或超时：操作可能已在后台执行，请先到空间里查看结果，不要重复提交'
}

/** 引擎历史消息（GET conversations/{chatId}/messages 的 data 元素）里前端用到的字段 */
export interface HistoryMessage {
  id: string
  role: string
  content: string
  steps?: { name?: string; content: string }[] | null
}

/**
 * 把引擎组装好的"轮"结构翻成页面 turns。ChatHistoryAssembler 保证 user/assistant 交替，
 * 防御两处：开头就是 assistant（没有归属提问）跳过；同一轮出现连续 assistant 时
 * answer 取最后一条文本、steps 只在非空时覆盖（空 steps 不擦除已还原的步骤）。
 * 历史步骤固定按工具步骤渲染（引擎只入库工具决策与工具响应，没有"思考"独立行）。
 */
export const assembleHistoryTurns = (messages: HistoryMessage[]): AssistantTurn[] => {
  const turns: AssistantTurn[] = []
  for (const message of messages) {
    if (message.role === 'user') {
      turns.push({ question: message.content, answer: '', steps: [], running: false })
      continue
    }
    if (message.role !== 'assistant' || turns.length === 0) {
      continue
    }
    const turn = turns[turns.length - 1]
    turn.answer = message.content
    const steps = (message.steps ?? []).map((step) => ({
      kind: 'tool' as const,
      name: step.name,
      content: step.content,
    }))
    if (steps.length > 0) {
      turn.steps = steps
    }
  }
  return turns
}
