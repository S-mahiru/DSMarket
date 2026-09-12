import request, { API_BASE, getToken } from '@/api/request'
import type { AdminBoard, AdminMessage, AdminSessionDetail } from '@/types/ai'

/**
 * 坐席工作台接口（C4 §4.2 / §4.5）。
 *
 * 全部走 `/admin/**` → `hasRole("ADMIN")`（SecurityConfig 一处把住），普通买家调用一律 403。
 * 401/403/429 的提示由 `request.ts` 拦截器统一负责，本文件不做二次包装。
 */

/** 三列表快照（§4.5）—— **看板的权威**，SSE 重连后必须先拉它再收增量（§4.8 原则①）。 */
export function fetchBoard(): Promise<AdminBoard> {
  return request.get('/admin/ai/support/sessions')
}

/** 会话详情（§4.5）：完整消息 + AI 对话回放 + 脱敏用户标识。 */
export function fetchDetail(sessionId: number): Promise<AdminSessionDetail> {
  return request.get(`/admin/ai/support/session/${sessionId}/messages`)
}

/** 接入（§4.2）。并发双接入时落败方拿 409（H8）—— 前端据此重新同步看板，不假装接入成功。 */
export function takeSession(sessionId: number): Promise<void> {
  return request.post(`/admin/ai/support/session/${sessionId}/take`)
}

/**
 * 标记已读（§4.5 决议 J）：清本会话红点。
 * **不回推买家**，所以服务端不产生任何 SSE 事件 —— 别在这里等一个永远不来的回执。
 */
export function markSessionRead(sessionId: number): Promise<{ cleared: number }> {
  return request.post(`/admin/ai/support/session/${sessionId}/read`)
}

/** 坐席回复（§4.4）：≤4000 字；**回复不自动关会话**（P2 决议，H25）。 */
export function replySession(sessionId: number, content: string): Promise<AdminMessage> {
  return request.post(`/admin/ai/support/session/${sessionId}/message`, { content })
}

/** 结束会话（§4.5）：PH 任一态 → closed，并向买家推 `human_close`（H26）。 */
export function closeSession(sessionId: number): Promise<void> {
  return request.post(`/admin/ai/support/session/${sessionId}/close`)
}

/** 心跳（§4.2）：15s 一次，刷新服务端 45s TTL 的在线键。`newlyOnline` = 本次"由离线转在线"。 */
export function sendHeartbeat(): Promise<{ newlyOnline: boolean }> {
  return request.post('/admin/ai/support/heartbeat')
}

/** 显式登出（§4.2 在线退出③）：清在线键 + 推 `seat_status(offline, logout)`。 */
export function seatLogout(): Promise<void> {
  return request.post('/admin/ai/support/logout')
}

/**
 * 关闭标签页时的离线上报（§4.2）。
 *
 * **不能用 `navigator.sendBeacon`** —— 它不能自定义请求头，而 `JwtAuthenticationFilter`
 * 只认 `Authorization`，beacon 过去必然 401，等于压根没上报。`fetch(..., {keepalive:true})`
 * 是唯一能把"带鉴权的请求"发完在页面卸载之后的写法。
 */
export function seatLogoutOnUnload(): void {
  const token = getToken()
  void fetch(`${API_BASE}/admin/ai/support/logout`, {
    method: 'POST',
    keepalive: true,
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  }).catch(() => {})
}
