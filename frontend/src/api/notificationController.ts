import request from '@/request'

/**
 * 通知接口（后端 SocialController 的 /notification/*，顶栏铃铛在用）。
 *
 * 这批函数原先手工追加在生成的 `postController.ts` 末尾；帖子/点赞/关注模块于 2026-09-15 停用、
 * 那个混装文件整体删除，它们是其中唯一仍在服务的部分，故迁到本文件。生成器不会产出本文件，
 * 重跑 `npm run openapi` 也不会覆盖它。
 *
 * 刻意不收 `options` 形参：现有调用点都是直接调用（要透传 axios 配置时再加，届时用具体类型而不是 any，
 * 免得踩 `@typescript-eslint/no-explicit-any`）。
 */

/** listNotificationsUsingGet GET /api/notification/list */
export async function listNotificationsUsingGet(params: { current?: number; pageSize?: number }) {
  return request<API.BaseResponsePageUserNotification_>('/api/notification/list', {
    method: 'GET',
    params,
  })
}

/** getUnreadCountUsingGet GET /api/notification/unread */
export async function getUnreadCountUsingGet() {
  return request<API.BaseResponseLong_>('/api/notification/unread', {
    method: 'GET',
  })
}

/** markNotificationReadUsingPost POST /api/notification/read/:id */
export async function markNotificationReadUsingPost(id: number) {
  return request<API.BaseResponseBoolean_>(`/api/notification/read/${id}`, {
    method: 'POST',
  })
}

/** deleteNotificationUsingPost POST /api/notification/delete/:id */
export async function deleteNotificationUsingPost(id: number) {
  return request<API.BaseResponseBoolean_>(`/api/notification/delete/${id}`, {
    method: 'POST',
  })
}

/** clearNotificationsUsingPost POST /api/notification/clear */
export async function clearNotificationsUsingPost() {
  return request<API.BaseResponseBoolean_>('/api/notification/clear', {
    method: 'POST',
  })
}

/** clearUnreadUsingPost POST /api/notification/clear-unread */
export async function clearUnreadUsingPost() {
  return request<API.BaseResponseBoolean_>('/api/notification/clear-unread', {
    method: 'POST',
  })
}
