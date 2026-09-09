// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** publishPost POST /api/post/publish */
export async function publishPostUsingPost(
  body: API.PostAddRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseLong_>('/api/post/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/** listPostByPage POST /api/post/list/page */
export async function listPostByPageUsingPost(
  body: API.PostQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePagePostVO_>('/api/post/list/page', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/** getPostById GET /api/post/get/:id */
export async function getPostByIdUsingGet(id: number, options?: { [key: string]: any }) {
  return request<API.BaseResponsePostVO_>(`/api/post/get/${id}`, {
    method: 'GET',
    ...(options || {}),
  })
}

/** editPost POST /api/post/edit */
export async function editPostUsingPost(
  body: API.PostEditRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean_>('/api/post/edit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/** deletePost POST /api/post/delete */
export async function deletePostUsingPost(
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean_>('/api/post/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

// ========= 点赞 =========

export async function togglePostLikeUsingPost(body: { postId: number }, options?: any) {
  return request<API.BaseResponseBoolean_>('/api/post/like', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

export async function getPostLikeCountUsingGet(postId: number, options?: any) {
  return request<API.BaseResponseLong_>(`/api/post/like/count/${postId}`, { method: 'GET', ...(options || {}) })
}

export async function getPostLikeStatusUsingGet(postId: number, options?: any) {
  return request<API.BaseResponseBoolean_>(`/api/post/like/status/${postId}`, { method: 'GET', ...(options || {}) })
}

// ========= 评论 =========

export async function addPostCommentUsingPost(
  body: { postId: number; parentId?: number; replyToUserId?: number; content: string },
  options?: any
) {
  return request<API.BaseResponseLong_>('/api/post/comment/add', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

export async function listPostCommentsUsingGet(postId: number, options?: any) {
  return request<API.BaseResponseListObject_>(`/api/post/comment/list/${postId}`, {
    method: 'GET',
    ...(options || {}),
  })
}

export async function deletePostCommentUsingPost(body: { id: number }, options?: any) {
  return request<API.BaseResponseBoolean_>('/api/post/comment/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

// ========= 关注 =========

export async function toggleFollowUsingPost(body: { followeeId: number }, options?: any) {
  return request<API.BaseResponseBoolean_>('/api/user/follow/toggle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

export async function getFollowStatusUsingGet(followeeId: number, options?: any) {
  return request<API.BaseResponseBoolean_>(`/api/user/follow/status/${followeeId}`, {
    method: 'GET',
    ...(options || {}),
  })
}

export async function listFollowingUsingGet(options?: any) {
  return request<API.BaseResponseListUserVO_>('/api/user/follow/following/list', {
    method: 'GET',
    ...(options || {}),
  })
}

export async function getFollowingCountUsingGet(userId: number, options?: any) {
  return request<API.BaseResponseLong_>(`/api/user/follow/following/count/${userId}`, {
    method: 'GET',
    ...(options || {}),
  })
}

export async function getFollowerCountUsingGet(userId: number, options?: any) {
  return request<API.BaseResponseLong_>(`/api/user/follow/follower/count/${userId}`, {
    method: 'GET',
    ...(options || {}),
  })
}

// ========= 动态流 & 通知 =========

export async function getTimelineUsingGet(params: { current?: number; pageSize?: number }, options?: any) {
  return request<API.BaseResponsePagePostVO_>('/api/timeline', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

export async function listNotificationsUsingGet(params: { current?: number; pageSize?: number }, options?: any) {
  return request<API.BaseResponsePageUserNotification_>('/api/notification/list', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

export async function getUnreadCountUsingGet(options?: any) {
  return request<API.BaseResponseLong_>('/api/notification/unread', {
    method: 'GET',
    ...(options || {}),
  })
}

export async function markNotificationReadUsingPost(id: number, options?: any) {
  return request<API.BaseResponseBoolean_>(`/api/notification/read/${id}`, {
    method: 'POST',
    ...(options || {}),
  })
}

export async function deleteNotificationUsingPost(id: number, options?: any) {
  return request<API.BaseResponseBoolean_>(`/api/notification/delete/${id}`, {
    method: 'POST',
    ...(options || {}),
  })
}

export async function clearNotificationsUsingPost(options?: any) {
  return request<API.BaseResponseBoolean_>('/api/notification/clear', {
    method: 'POST',
    ...(options || {}),
  })
}

export async function clearUnreadUsingPost(options?: any) {
  return request<API.BaseResponseBoolean_>('/api/notification/clear-unread', {
    method: 'POST',
    ...(options || {}),
  })
}
