import request from '@/api/request'
import { consumeSse, parseSseEvent } from '@/api/sse'
import type { SseEvent } from '@/api/sse'
import type {
  ChatContextPayload,
  SupportOrigin,
  SupportRequestResult,
  SupportSendResult,
  SupportSessionSnapshot
} from '@/types/ai'

/**
 * AI 客服买家侧接口（C1 §2 `/chat` / C3 `/feedback` / C4 §4.4 `/support/**`）。
 *
 * **`/chat` 不走 axios 实例**，直接 `fetch` 消费 SSE —— 理由见 `api/sse.ts` 顶部：
 * axios 的 `timeout: 15000` 会掐断长生成，且它会缓冲整个响应体（流式就没了）。
 * 其余三个都是普通 HTTP，照常走 axios（顺带白拿统一错误提示）。
 */

/** 每个用户消息一枚幂等键，**复用同一条消息重试时才重发** —— 每次重试都换新键等于没有幂等 */
function newClientMsgId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export interface RunChatTurnOptions {
  content: string
  /** 发送时读取（不是打开抽屉时）—— 页面可能已经变了 */
  context?: ChatContextPayload
  signal: AbortSignal
  /** 已解析的 SSE 事件；坏帧不会走到这里（`parseSseEvent` 已丢弃并留痕） */
  onEvent: (evt: SseEvent) => void
}

/**
 * 跑一轮 AI 问答，直到流结束或 `signal` 被 abort。
 *
 * **本函数不判断"这一轮是好是坏"** —— 收尾分类需要知道调用方的意图（用户是否主动停、
 * 是否已切人工通道），只有 store 有这些信息。这里只保证：正常结束就正常返回，
 * 开流前的 400/401/409/429 抛 `SseHttpError`（含服务端 message）。
 */
export async function runChatTurn(opts: RunChatTurnOptions): Promise<void> {
  const body: Record<string, unknown> = {
    content: opts.content,
    clientMsgId: newClientMsgId()
  }
  // 只发白名单 page；没有 context 时**整个字段不发**，而不是发 `context: {}`
  if (opts.context) body.context = opts.context

  await consumeSse({
    path: '/ai/chat',
    method: 'POST',
    body,
    signal: opts.signal,
    onData: (raw) => {
      const evt = parseSseEvent(raw, 'chat')
      if (evt) opts.onEvent(evt)
    }
  })
}

/**
 * 买家反馈（C3-F2）。点赞仅日志；点踩凭 replyId 回查原问句入问题池。
 *
 * 接口对"回查不到 replyId"也是 200 静默忽略（防遍历探测），所以**前端不据响应判成败**。
 */
export function sendFeedback(replyId: string, satisfied: boolean, reason?: string): Promise<void> {
  const body: Record<string, unknown> = { replyId, satisfied }
  if (reason) body.reason = reason
  return request.post('/ai/feedback', body)
}

/**
 * 转人工请求的**专属超时**（axios 实例默认 15s，见 `request.ts:15`）。
 *
 * **为什么单独压短**：请求在飞期间买家侧**禁用输入框**（`AiSupportDrawer` 的 `busy`
 * 含 `transferring`）—— 因为通道在服务端建行之前**不可知**：
 * `AiSupportSessionServiceImpl:154-157` 对"没有会话行"的 `/support/message` 直接 409，
 * 所以不能乐观地猜"点了转人工，消息就一定能走人工通道"。
 * 于是这个值 = **买家最多被挡住多久**，15s 太久，压到 5s。
 *
 * **超时不会撤销服务端效果**（行可能已经建了）：前端仍停在 AI 态，下一条消息走 `/chat`
 * 会被后端 `routedToHuman` 兜住（`AiController:121-129`）→ `fallback + done(human-routed)`
 * → store 的哨兵分支丢掉本地气泡并重拉快照。**与竞态走同一条收敛路径**，故代价有界。
 */
const TRANSFER_TIMEOUT_MS = 5000

/**
 * 发起转人工（C4-F1，幂等：已有 PH 会话 → 返回现状不新建）。
 *
 * ⚠️ **`origin` 是 query 参数，不是请求体** —— 实现是
 * `@RequestParam(required = false) String origin`（`AiSupportController#request`），
 * 而 C4 §4.1 没记这一点（REQ-20260910 §12 A3）。写成 body 会被**静默忽略**、
 * 缺省补成 `USER_REQUEST`，于是"点建议气泡转人工"的 origin 永远是错的，
 * 入口分流效果就再也统计不出来 —— 不会报错，只会一直错。
 */
export function requestHumanSupport(origin: SupportOrigin): Promise<SupportRequestResult> {
  return request.post('/ai/support/request', null, {
    params: { origin },
    timeout: TRANSFER_TIMEOUT_MS
  })
}

/** 人工态发消息（≤2000，C4 §4.4）。409 `SESSION_CLOSED`、429 限流由拦截器统一提示。 */
export function sendSupportMessage(content: string, clientMsgId?: string): Promise<SupportSendResult> {
  const body: Record<string, unknown> = { content }
  if (clientMsgId) body.clientMsgId = clientMsgId
  return request.post('/ai/support/message', body)
}

/**
 * 买家会话快照（C4 §4.4）—— **人工历史的唯一来源**。
 * SSE 只推连接期间的新事件、不重放历史，所以每次（重）连都必须重拉它。
 */
export function fetchSupportSession(): Promise<SupportSessionSnapshot> {
  return request.get('/ai/support/session')
}
