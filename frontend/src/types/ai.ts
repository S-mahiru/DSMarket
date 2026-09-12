/**
 * AI 客服前端的共享类型（C1 §3 AI 态事件 / C4 §4.4 快照 / C4 §4.7 买家人工态）。
 *
 * 权威在后端：本文件只做"前端怎么消费"的映射，任何字段含义有疑问一律回读
 * `REQ-20260907-…对话引擎链路.md` 与 `REQ-20260908-…转人工与坐席工作台.md`，不要在这里发明。
 */

/** `context.page` 白名单（C1 §2 严格枚举）。**传白名单外的值会在开流前 400**，故其余页面干脆不传。 */
export type ChatPage = 'home' | 'order_list' | 'order_detail' | 'product_detail'

export interface ChatContextPayload {
  page: ChatPage
  /** page=order_detail 时必带（ChatScenario 对不属于本人的 orderNo 走"忽略"，不报错） */
  orderNo?: string
  /** page=product_detail 时必带 */
  productId?: number
}

/**
 * `done.replyId` 的哨兵值：本轮在 `/chat` 入口被 C4 分流到人工通道
 * （`AiChatStreamService.REPLY_ID_HUMAN_ROUTED`）。
 *
 * **它不是一次 AI 轮次** —— 后端没有这一轮的会话记录，拿它去 `/feedback` 只会回查失败，
 * 所以这种气泡**不给赞踩**。
 */
export const REPLY_ID_HUMAN_ROUTED = 'human-routed'

/** 转人工途径（C4 §4.1）。买家可自报的只有这两个，`ANCHOR_HIT` 是服务端内部途径、买家接口传了会 400。 */
export type SupportOrigin = 'USER_REQUEST' | 'AI_SUGGEST'

/**
 * 人工会话状态（C4 §3，小写蛇形，铁律）。
 *
 * `ai_active` **不在本联合类型里**：它是隐含态、本表不落库，纯 AI 对话时没有会话行
 * （`exists=false`）。想表达"纯 AI 态"用它，不要发明一个 `'ai_active'` 字符串。
 */
export type HumanStatus = 'pending_human' | 'human_active' | 'message_left' | 'closed'

export type SupportSender = 'USER' | 'AGENT' | 'SYSTEM'

export interface SupportMessageVO {
  messageId: number
  sender: SupportSender
  content: string
  createdAt: string
}

/** `GET /ai/support/session` —— **人工历史的唯一来源**（SSE 只推连接期间的新事件、不重放历史）。 */
export interface SupportSessionSnapshot {
  exists: boolean
  /** exists=false 时为 null/undefined */
  status?: HumanStatus
  /** 服务端下发的中文状态名（"等待接入"/"人工处理中"/"已留言"/"已结束"），原样展示 */
  statusName?: string
  sessionId?: number
  requestedAt?: string
  activeAt?: string
  /** 仅 status=closed 时有值 */
  closedAt?: string
  messages: SupportMessageVO[]
}

/** `POST /ai/support/request` 的响应（C4 §4.1 步骤 4）。 */
export interface SupportRequestResult {
  /** pending_human（有人在线，等待接入）/ message_left（无人在线，引导留言） */
  status: 'pending_human' | 'message_left'
  /** **服务端下发的提示语**，原样展示；前端不得自编"正在为您接入人工客服…" */
  tip: string
  /** true = 立刻展开留言引导 */
  promptLeave: boolean
  sessionId: number
}

/** `POST /ai/support/message` 的响应（C4 §4.4）。status 是**写入之后**的会话状态。 */
export interface SupportSendResult {
  messageId: number
  sessionId: number
  status: HumanStatus
  /** true = clientMsgId 幂等命中，本次未落库 */
  duplicate: boolean
}

/**
 * 一轮 AI 生成的收尾态（REQ-20260910 §4.1 规则 8）。
 *
 * **这五个值不得合并** —— 服务端对"用户主动停止"与"转人工打断"都**不补 `done`**，
 * 把它们与真错误一视同仁，会让用户每点一次停止就看到一次报错。
 */
export type TurnOutcome = 'streaming' | 'done' | 'stopped' | 'interrupted' | 'error'

export interface ChatMessage {
  /** 本地渲染 key（AI 轮用序号，人工消息用 `m{messageId}`）——不是任何协议 id */
  id: string
  /** 通道：AI 态与人工态**物理隔离、不混渲染**（C4 §4.7） */
  channel: 'ai' | 'human'
  role: 'user' | 'assistant' | 'system'
  content: string
  createdAt?: string
  /** 人工消息的服务端主键 = 快照去重键 */
  messageId?: number
  /** 本地乐观消息发送失败（未落库） */
  failed?: boolean

  // ---------------------------------------------------------- AI 通道专有
  /** `tool_begin.label`（服务端下发，前端不得自编），渲染在正文上方 */
  tools?: string[]
  /** `fallback` 兜底回复 → 渲染"（自动回复）"灰标 */
  fallback?: boolean
  /**
   * `suggest.reason`，**原样透传**。
   *
   * 后端三种口径尚未收敛（REQ-20260910 §12 A6：C1 §3 表列 3 值 / C1 §5 标注只 LOW_CONF /
   * 实现实际发 LOW_CONF+UNRESOLVED），所以这里只当字符串存、只当字符串显示 ——
   * 硬编码枚举会让将来任何一次裁决变成"前端静默丢气泡"。
   */
  suggestReason?: string
  /** `done.replyId`；等于 `REPLY_ID_HUMAN_ROUTED` 时**不是**可反馈的轮次 */
  replyId?: string
  turn?: TurnOutcome
  errorText?: string
  feedback?: 'up' | 'down'
}

/** 当前人工会话（抽屉顶栏据此切换 UI；null = 无会话行或尚未拉到快照）。 */
export interface HumanSessionState {
  status: HumanStatus
  /** 快照下发的中文名；来自 `request()` 响应时可能缺失（该接口不回 statusName） */
  statusName?: string
  sessionId: number
}

// ---------------------------------------------------------------- 坐席工作台（C4 §4.5 三列表 / §4.8 admin 通道）

/** 工作台三列表的一行（后端 `AdminSupportSessionVO`）。 */
export interface AdminSessionSummary {
  sessionId: number
  /** 服务端**已脱敏**（如 `a***t`）；工作台不做用户详情页，前端不要再拼完整标识 */
  userMasked: string
  /** USER_REQUEST / ANCHOR_HIT / AI_SUGGEST。注意后端**只发枚举值、不发中文名** */
  origin: string
  status: HumanStatus
  statusName: string
  requestedAt?: string
  activeAt?: string
  closedAt?: string
  lastMsgAt?: string
  /** 该会话中买家发出且坐席未读的条数（§4.5 红点） */
  unreadCount: number
}

/** `GET /admin/ai/support/sessions` —— **看板的权威快照**；SSE 只推增量、不重放历史。 */
export interface AdminBoard {
  /** status=pending_human */
  queued: AdminSessionSummary[]
  /** status=human_active */
  active: AdminSessionSummary[]
  /** status=message_left */
  left: AdminSessionSummary[]
}

/**
 * 工作台消息（后端 `AdminSupportMessageVO`）。
 *
 * 与买家侧的 `SupportMessageVO` **是两个类型**：这个多一个 `readByAgent`
 * （J 决议：已读回执不回推买家，所以买家侧类型里根本没有这个字段）。
 */
export interface AdminMessage {
  messageId: number
  sender: SupportSender
  content: string
  createdAt: string
  readByAgent?: boolean
  /** 本地乐观占位（此时 `messageId` 为负数），尚未拿到服务端主键 */
  pending?: boolean
}

/** AI 对话回放条目（C4 §4.5 L 决议：原文直读近 N 轮，不生成摘要、不调模型）。 */
export interface AiSummaryItem {
  userContent: string
  assistantContent: string
  ts: number
}

/** `GET /admin/ai/support/session/{id}/messages`。 */
export interface AdminSessionDetail {
  sessionId: number
  userMasked: string
  origin: string
  status: HumanStatus
  statusName: string
  requestedAt?: string
  activeAt?: string
  closedAt?: string
  lastMsgAt?: string
  messages: AdminMessage[]
  /**
   * **空数组 = 取不到**（Redis 会话已过期，或转人工前压根没聊过）→ 显示"无 AI 对话记录"。
   * 这是"取不到就说取不到"：**不得**拿本会话的消息冒充 AI 回放。
   */
  aiSummary: AiSummaryItem[]
}

/**
 * 坐席本机的在线态。
 *
 * 服务端**只**在 login / logout 两个因上推 `seat_status`，**没有 `heartbeat_timeout` 事件**
 * （`SupportEvents.REASON_*` 只有两个常量），所以"心跳断了"只能前端拿本地时间自判。
 */
export type SeatState = 'unknown' | 'online' | 'offline'
