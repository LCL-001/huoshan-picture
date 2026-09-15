/**
 * 表格勾选 → 图片 id 列表（AI 打标用）。
 *
 * 为什么要有这层（2026-09-15 现场 bug）：
 * a-table 的 rowSelection 给出的 key 取决于 row-key；页面**没配 row-key 时**默认取记录的 `key` 字段，
 * 而图库记录里没有这个字段 ⇒ 勾选拿到的是 `undefined`，直接 `String()` 就是字面量 `"undefined"`，
 * 发给后端后 Jackson 解析 `List<Long>` 失败 ⇒ 50000「系统错误」（日志：Cannot deserialize ... from String "undefined"）。
 *
 * 另外：雪花 id 超出 JS 安全整数范围，**number 形态的 id 宁可丢弃也不能转字符串**——
 * `String(2098042858971074566)` 会得到被截断的 `"2098042858971074600"`，等于把另一张图的 id 发出去。
 */
export function toPictureIdList(keys: unknown): string[] {
  if (!Array.isArray(keys)) {
    return []
  }
  const ids: string[] = []
  for (const key of keys) {
    if (typeof key === 'string') {
      const trimmed = key.trim()
      if (trimmed) {
        ids.push(trimmed)
      }
      continue
    }
    if (typeof key === 'number' && Number.isSafeInteger(key)) {
      ids.push(String(key))
    }
    // 其余（undefined / null / 超出安全范围的 number / 对象）一律丢弃：宁可让用户重选，也不能发错 id
  }
  return ids
}

/** 合法的图片 id 列表：非空、且每项都是纯数字串（雪花 id 由图库序列化成字符串） */
export function isPictureIdList(ids: unknown): ids is string[] {
  return (
    Array.isArray(ids) && ids.length > 0 && ids.every((id) => typeof id === 'string' && /^\d+$/.test(id))
  )
}
