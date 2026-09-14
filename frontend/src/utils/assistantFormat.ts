/** 引擎 step 事件里工具步骤的固定套语（BaseAgent：`工具 X 完成了它的任务！结果：<payload>`） */
const TOOL_RESULT_MARK = '结果：'

/** 工具名 → 给人看的说法；表里没有就退回工具名本身 */
const TOOL_LABELS: Record<string, string> = {
  listSpaces: '查询空间列表',
  listPictures: '查询图片清单',
  getTagCategory: '读取标签词表',
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
  const payload = parsePayloadObject(content)
  if (!payload) {
    return '已完成'
  }
  if (payload.error === true) {
    const reason = typeof payload.message === 'string' ? payload.message : '未知原因'
    return `失败：${reason}`
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

export type TextBlock = { kind: 'paragraph' | 'bullet' | 'ordered' | 'quote'; items: InlinePart[][] }

/**
 * 只认模型实际会用的那几样：`**粗体**`、`` `行内代码` ``、http(s) 链接、`- `/`* ` 与 `1. ` 列表、`> ` 引用。
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
