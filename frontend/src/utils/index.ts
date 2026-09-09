import { saveAs } from 'file-saver'
import dayjs from 'dayjs'

const normalizeTimestamp = (value: number) => {
  return Math.abs(value) < 1_000_000_000_000 ? value * 1000 : value
}

const parseDateObject = (value: Record<string, unknown>) => {
  const rawTimestamp = value.time ?? value.timestamp ?? value.millis ?? value.epochMilli
  const timestamp = Number(rawTimestamp)
  if (rawTimestamp !== undefined && Number.isFinite(timestamp)) {
    return new Date(normalizeTimestamp(timestamp))
  }

  const year = Number(value.year)
  const month = value.monthValue !== undefined ? Number(value.monthValue) - 1 : Number(value.month)
  const day = Number(value.date ?? value.day ?? value.dayOfMonth)
  if (Number.isFinite(year) && Number.isFinite(month) && Number.isFinite(day)) {
    return new Date(
      year < 1000 ? year + 1900 : year,
      month,
      day,
      Number(value.hours ?? value.hour ?? 0),
      Number(value.minutes ?? value.minute ?? 0),
      Number(value.seconds ?? value.second ?? 0),
    )
  }

  return undefined
}

export const formatDateTime = (value: unknown, fallback = '-') => {
  if (value === undefined || value === null || value === '') {
    return fallback
  }

  let normalizedValue = value
  if (typeof value === 'number') {
    normalizedValue = new Date(normalizeTimestamp(value))
  } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    normalizedValue = new Date(normalizeTimestamp(Number(value)))
  } else if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0, second = 0] = value.map(Number)
    normalizedValue = new Date(year, month - 1, day, hour, minute, second)
  } else if (typeof value === 'object' && !(value instanceof Date)) {
    normalizedValue = parseDateObject(value as Record<string, unknown>)
  }

  const date = dayjs(normalizedValue)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : fallback
}

/**
 * 格式化文件大小
 * @param size
 */
export const formatSize = (size?: number) => {
  if (!size) return '未知'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + ' KB'
  return (size / (1024 * 1024)).toFixed(2) + ' MB'
}

/**
 * 格式化相对时间
 */
export const formatRelativeTime = (value: unknown): string => {
  if (!value) return ''
  const d = new Date(value as string)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffDays = Math.floor(diffMs / 86400000)
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`

  if (diffDays === 0) return `今天 ${time}`
  if (diffDays === 1) return `昨天 ${time}`
  if (diffDays === 2) return `前天 ${time}`
  if (diffDays <= 6) return `${diffDays}天前 ${time}`

  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  if (d.getFullYear() === now.getFullYear()) return `${month}-${day}`
  return `${d.getFullYear()}-${month}-${day}`
}

/**
 * 下载图片
 * @param url 图片下载地址
 * @param fileName 要保存为的文件名
 */
export function downloadImage(url?: string, fileName?: string) {
  if (!url) {
    return
  }
  saveAs(url, fileName)
}

/**
 * 将颜色值转换为标准 #RRGGBB 格式
 * @param input
 */
export function toHexColor(input: string) {
  // 去掉 0x 前缀
  const colorValue = input.startsWith('0x') ? input.slice(2) : input

  // 将剩余部分解析为十六进制数，再转成 6 位十六进制字符串
  const hexColor = parseInt(colorValue, 16).toString(16).padStart(6, '0')

  // 返回标准 #RRGGBB 格式
  return `#${hexColor}`
}

