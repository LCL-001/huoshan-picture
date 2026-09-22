/**
 * 批量抓取接口响应 → 结果卡片展示状态。
 *
 * 抽成纯函数是为了能被 `scripts/check-batch-crawl-result.mjs` 直接断言（前端没有测试框架，
 * 沿用 `assistantFormat.ts` + `check:assistant-format` 的做法）。
 *
 * 背景（T30）：后端"成功但一张都没入库"时返回 `{code: 0, data: 0, message: "ok"}`。
 * 老代码判的是 `code === 0 && data`，而 JS 里 `0` 是 falsy ⇒ 走失败分支，
 * 页面显示成"任务执行失败 / ok"。T29 起"重跑同一搜索词"稳定返回 0，这条路径变成最常见的一种。
 */

export type BatchTaskStatus = 'success' | 'empty' | 'error'

export interface BatchTaskResult {
  status: BatchTaskStatus
  title: string
  desc: string
}

export interface BatchCrawlResponse {
  code?: number
  data?: number
  message?: string
}

const TITLE_DONE = '任务执行完成'
const TITLE_FAILED = '任务执行失败'
const DESC_EMPTY =
  '没有新增图片：结果可能都已导入过，或该搜索词没有可用图片。换个搜索词、或调整偏移量后再试。'

/**
 * 把接口响应映射成展示状态：成功且有新增 = success；成功但 0 张（或没带数量）= empty；
 * 业务码非 0 = error（描述用后端给的原因，缺省"请稍后重试"）。
 *
 * 注意判的是 `code === 0` 而不是 `code === 0 && data`：后者会把 `data = 0` 当成失败（`0` 是 falsy）。
 */
export function mapBatchCrawlResponse(response: BatchCrawlResponse): BatchTaskResult {
  if (response.code === 0) {
    const uploaded = typeof response.data === 'number' ? response.data : 0
    if (uploaded > 0) {
      return { status: 'success', title: TITLE_DONE, desc: `成功抓取并上传 ${uploaded} 张图片` }
    }
    // 成功但一张都没新增：通常是源地址都导入过了（T29 判重）或搜索词没有可用图片
    return { status: 'empty', title: TITLE_DONE, desc: DESC_EMPTY }
  }
  return { status: 'error', title: TITLE_FAILED, desc: response.message ?? '请稍后重试' }
}
