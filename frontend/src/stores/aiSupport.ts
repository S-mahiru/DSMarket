import { create } from 'zustand'
import {
  fetchSupportSession,
  requestHumanSupport,
  runChatTurn,
  sendFeedback,
  sendSupportMessage
} from '@/api/ai'
import type { SseEvent } from '@/api/sse'
import { REPLY_ID_HUMAN_ROUTED } from '@/types/ai'
import type {
  ChatContextPayload,
  ChatMessage,
  HumanSessionState,
  HumanStatus,
  SupportOrigin,
  SupportSessionSnapshot
} from '@/types/ai'

/**
 * AI 客服抽屉的状态（买家侧：AI 态 + 人工态）。
 *
 * **为什么状态不放在抽屉组件里**：抽屉关着的时候，常驻的买家人工通道仍在收事件
 * （`human_close`、坐席回复）。放进组件 state，`destroyOnClose` 一改或组件一重挂，
 * 那段时间的消息就没了。
 *
 * **两条通道在同一个列表里，但 `channel` 分开且不混渲染**（C4 §4.7 硬规则）：
 * 人工态不出现 `delta`/`suggest`/`tool_begin` —— 那些字段只可能挂在 `channel==='ai'` 的消息上。
 */

/** 本地自增序号：只用于渲染 key 与本地定位，**不是**任何协议 id */
let seq = 0
const nextId = (prefix: string) => `${prefix}${++seq}`

/**
 * 正在飞的那一轮。放模块级而不是 state：
 * `AbortController` 不是渲染数据，塞进 state 只会让每一次 set 都拖着它走。
 *
 * `reason` 是**收尾分类的依据**：同样是"流被 abort 了"，用户点停止是"已停止"，
 * 点了转人工则是"已中断"（C1 E11），两者都不报错，但文案不同。少了它就只能二选一，
 * 要么把转人工说成"你停了"，要么把它说成"出错了"。
 */
let inflight: { controller: AbortController; reason: 'stop' | 'transfer' } | null = null

function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/**
 * 是否处于人工态。
 *
 * **`closed` 不算**：终态之后 `/chat` 会回到 AI 流程（`AiSupportSessionStatus.PH_VALUES`
 * 只含 pending_human/human_active/message_left），所以会话结束后输入框该走 AI 通道，
 * 而不是继续往 `/support/message` 打（那个接口对 closed 是 409）。
 */
function isHumanMode(human: HumanSessionState | null): boolean {
  return !!human && human.status !== 'closed'
}

function senderRole(sender: string): ChatMessage['role'] {
  if (sender === 'USER') return 'user'
  if (sender === 'SYSTEM') return 'system'
  return 'assistant' // AGENT
}

function toHumanMessage(v: SupportSessionSnapshot['messages'][number]): ChatMessage {
  return {
    id: `m${v.messageId}`,
    channel: 'human',
    role: senderRole(v.sender),
    content: v.content,
    messageId: v.messageId,
    createdAt: v.createdAt
  }
}

interface AiSupportState {
  /** 抽屉是否打开 */
  open: boolean

  /** 抽屉关着时收到的人工消息条数（打开即清零） */
  unread: number

  messages: ChatMessage[]

  /** 一轮 AI 生成在飞（单飞行；此间输入框禁用） */
  sending: boolean
  /** 一条人工消息在飞 */
  sendingHuman: boolean
  /** 转人工请求在飞 */
  transferring: boolean

  /** 当前人工会话；null = 没有会话行（纯 AI 态） */
  human: HumanSessionState | null
  /** 快照是否已成功拉取（未拉到时抽屉不渲染输入框，避免用错误的通道发第一条消息） */
  snapshotLoaded: boolean
  /** 快照拉取失败（抽屉据此显示"加载失败 + 重试"，而不是一直转圈） */
  snapshotFailed: boolean

  /** 最近一次由服务端下发的提示语（`request` 的 tip / `human_close` 的 text），原样展示 */
  statusText: string | null

  setOpen: (open: boolean) => void
  send: (content: string, context?: ChatContextPayload) => Promise<void>
  stop: () => void
  submitFeedback: (messageId: string, satisfied: boolean) => Promise<void>
  transfer: (origin: SupportOrigin) => Promise<void>
  sendHuman: (content: string) => Promise<void>
  syncSnapshot: () => Promise<void>
  handleStreamEvent: (evt: SseEvent) => void
  reset: () => void
}

export const useAiSupportStore = create<AiSupportState>((set, get) => ({
  open: false,
  unread: 0,
  messages: [],
  sending: false,
  sendingHuman: false,
  transferring: false,
  human: null,
  snapshotLoaded: false,
  snapshotFailed: false,
  statusText: null,

  setOpen(open) {
    set(open ? { open, unread: 0 } : { open })
  },

  async send(content, context) {
    const text = content.trim()
    const s0 = get()
    if (!text || s0.sending || s0.sendingHuman) return

    // 已在人工态 → 走人工通道。服务端在 `/chat` 入口也会做同样的分流（返回 fallback+done），
    // 但那条路上气泡会被错渲染成"AI 回答"，而且会白开一条流 —— 前端先分流更干净。
    if (isHumanMode(s0.human)) {
      await get().sendHuman(text)
      return
    }

    const userMsgId = nextId('u')
    const aiMsgId = nextId('a')
    const controller = new AbortController()
    inflight = { controller, reason: 'stop' }

    set((st) => ({
      sending: true,
      messages: [
        ...st.messages,
        { id: userMsgId, channel: 'ai', role: 'user', content: text },
        { id: aiMsgId, channel: 'ai', role: 'assistant', content: '', tools: [], turn: 'streaming' }
      ]
    }))

    // 累积在局部变量里，一帧一次 set —— 每收到一个 delta 就重新 map 整个数组，
    // 在流式下是每秒几十次的整表复制。
    let acc = ''
    let tools: string[] = []
    let replyId = ''
    let fallbackText = ''
    let errorText = ''
    let done = false

    const patch = (p: Partial<ChatMessage>) =>
      set((st) => ({ messages: st.messages.map((m) => (m.id === aiMsgId ? { ...m, ...p } : m)) }))

    try {
      await runChatTurn({
        content: text,
        context,
        signal: controller.signal,
        onEvent: (evt) => {
          switch (evt.type) {
            case 'tool_begin': {
              const label = str(evt.label)
              // 文案是服务端下发的（如"正在查询您的订单…"），前端**不得自编** —— 与 tip 同一条纪律
              if (label) {
                tools = [...tools, label]
                patch({ tools })
              }
              break
            }
            case 'delta':
              acc += str(evt.delta)
              patch({ content: acc })
              break
            case 'fallback':
              fallbackText = str(evt.content)
              patch({ content: fallbackText, fallback: true })
              break
            case 'suggest':
              patch({ suggestReason: str(evt.reason) })
              break
            case 'done':
              done = true
              replyId = str(evt.replyId)
              break
            case 'error':
              errorText = str(evt.message) || '生成失败'
              break
            default:
              // 未知类型**不当作错误**：协议只会增事件，多一种类型不该让整轮变红
              console.warn('[ai-chat] 未知事件类型（已忽略）：', evt.type)
          }
        }
      })
    } catch (e) {
      // 走到这里 = 开流前的失败（400 超长 / 401 / 409 已有轮次在飞 / 429 限流），
      // 此时**还没有 SSE**，所以拿得到状态码与 message。用户主动 abort 不算（下面按 aborted 判）。
      if (!controller.signal.aborted) {
        errorText = e instanceof Error ? e.message : '请求失败'
      }
    }

    // 分类要用 abort 的原因，所以这里先取出来（下一行就把 inflight 清了）
    const abortReason = inflight?.reason ?? 'stop'
    inflight = null

    // ── 收尾分类（REQ-20260910 §4.1 规则 8）：**四种收尾不得合并** ──
    if (replyId === REPLY_ID_HUMAN_ROUTED) {
      // 本轮已在后端入口被分流到人工通道，且那句话**已经落成首条人工消息**。
      // 本地这两条要整体丢掉，否则会和随后快照里的同一条重一遍。
      set((st) => ({
        sending: false,
        messages: st.messages.filter((m) => m.id !== userMsgId && m.id !== aiMsgId)
      }))
      if (fallbackText) set({ statusText: fallbackText })
      await get().syncSnapshot()
      return
    }

    if (done) {
      patch({ turn: 'done', replyId })
    } else if (controller.signal.aborted) {
      // C1 E10/E11：用户主动停止，或点了转人工把在途的一轮打断。
      // 两种情况下服务端**都不补 done** —— 这是正常收尾，不报错。
      patch({ turn: abortReason === 'transfer' ? 'interrupted' : 'stopped' })
    } else if (errorText) {
      patch({ turn: 'error', errorText })
    } else {
      // EOF 而没有 done = 真错误。这是唯一一条"看起来像正常结束其实是故障"的路径：
      // 开流之后的 BusinessException 走 isStreaming→noBody，**连状态码都拿不到**，
      // 表现就是流安静地结束。不补这一句，气泡会一直转圈。
      patch({ turn: 'error', errorText: '连接中断，未收到完整回复' })
    }
    set({ sending: false })
  },

  stop() {
    // 只是 abort。收尾态由 `send()` 里那段统一判 —— 停止不等于失败。
    if (inflight) {
      inflight.reason = 'stop'
      inflight.controller.abort()
    }
  },

  async submitFeedback(messageId, satisfied) {
    const msg = get().messages.find((m) => m.id === messageId)
    if (!msg?.replyId) return
    // 哨兵不是一次真实轮次（后端没有这一轮的会话记录），点了也只会回查失败 → 干脆不给按钮、也不发请求
    if (msg.replyId === REPLY_ID_HUMAN_ROUTED) return

    set((st) => ({
      messages: st.messages.map((m) => (m.id === messageId ? { ...m, feedback: satisfied ? 'up' : 'down' } : m))
    }))
    try {
      await sendFeedback(msg.replyId, satisfied)
    } catch {
      // 接口对"回查不到"也是 200；走到这里只可能是网络/鉴权故障，拦截器已提示过，不回滚 UI
    }
  },

  async transfer(origin) {
    if (get().transferring) return
    // 在途的一轮要先打断并标成"已中断"（C1 E11）：转人工之后服务端会把这一轮的结果作废、
    // **静默关流不发任何事件**，若不主动 abort，前端只能靠"EOF 而没有 done"收场 ——
    // 那正好落进"真错误"那一档，用户会在转人工成功的同时看到一条报错。
    if (inflight) {
      inflight.reason = 'transfer'
      inflight.controller.abort()
    }
    set({ transferring: true })
    try {
      const res = await requestHumanSupport(origin)
      set({
        human: { status: res.status, sessionId: res.sessionId },
        statusText: res.tip
      })
      // **刻意不在这里重拉快照**：无坐席在线时 request 返回 `message_left`，而 DB 行仍是
      // `pending_human` —— 立刻 GET /session 会把刚显示的"请留言"打回"正在接入"，
      // 正好违反本模块"不谎报有人接"的底线。信 POST 响应，等首条留言落库或下次（重）连再对齐。
    } catch {
      // 400（origin 非法）/ 401 / 409：拦截器已弹提示。这里只负责收尾，不改任何状态
    } finally {
      set({ transferring: false })
    }
  },

  async sendHuman(content) {
    const text = content.trim()
    if (!text || get().sendingHuman) return

    const localId = nextId('h')
    set((st) => ({
      sendingHuman: true,
      // 买家自己发的消息**没有 SSE 回显**（`/support/message` 只推给 admin 通道）→ 必须本地追加。
      // 去重靠下一次快照按 messageId 合并（见 syncSnapshot）。
      messages: [...st.messages, { id: localId, channel: 'human', role: 'user', content: text }]
    }))

    try {
      const res = await sendSupportMessage(text)
      set((st) => ({
        messages: st.messages.map((m) => (m.id === localId ? { ...m, messageId: res.messageId } : m)),
        // status 是**写入之后**的状态（pending_human 且仍无人在线 → 本条落库同时降到 message_left）
        human: st.human ? { ...st.human, status: res.status } : st.human
      }))
    } catch {
      // 409 SESSION_CLOSED / 429：本条**未落库**，据实标记失败，不装作发出去了
      set((st) => ({
        messages: st.messages.map((m) => (m.id === localId ? { ...m, failed: true } : m))
      }))
    } finally {
      set({ sendingHuman: false })
    }
  },

  async syncSnapshot() {
    set({ snapshotFailed: false })
    try {
      const snap = await fetchSupportSession()
      set((st) => {
        if (!snap.exists) {
          // H23：没有人工会话行 = 纯 AI 态 → 人工消息**全部清掉**，不残留旧 UI
          return {
            human: null,
            snapshotLoaded: true,
            messages: st.messages.filter((m) => m.channel === 'ai')
          }
        }
        const known = new Set(
          st.messages.map((m) => m.messageId).filter((id): id is number => id != null)
        )
        const fresh = snap.messages.filter((v) => !known.has(v.messageId)).map(toHumanMessage)
        return {
          human: {
            status: snap.status ?? 'pending_human',
            statusName: snap.statusName,
            sessionId: snap.sessionId ?? 0
          },
          snapshotLoaded: true,
          snapshotFailed: false,
          messages: [...st.messages, ...fresh]
        }
      })
    } catch {
      // 拉不到**不置 snapshotLoaded** —— 那会让抽屉在没有会话信息的情况下渲染出输入框，
      // 用户可能在错误的通道上发第一条消息。改为据实标记失败，抽屉显示"重试"，
      // 重连时的 onConnected 也会再拉一次。
      set({ snapshotFailed: true })
    }
  },

  handleStreamEvent(evt) {
    switch (evt.type) {
      case 'human_status': {
        const state = str(evt.state) as HumanStatus
        // 只更新状态，**不渲染 `text`**：该文案与紧随其后的 SYSTEM human_message 是同一个字符串
        // （`AiSupportAdminServiceImpl#take` 两处都发 TAKE_NOTICE_TEXT），渲染两遍会重复。
        set((st) => (st.human && state ? { human: { ...st.human, status: state } } : {}))
        break
      }

      case 'human_message': {
        const messageId = num(evt.messageId)
        if (messageId == null) break
        set((st) => {
          // 快照已经拉过这条（或事件重放）→ 丢弃，不重复渲染
          if (st.messages.some((m) => m.messageId === messageId)) return {}
          return {
            messages: [
              ...st.messages,
              {
                id: `m${messageId}`,
                channel: 'human',
                role: senderRole(str(evt.sender)),
                content: str(evt.content),
                messageId,
                createdAt: str(evt.createdAt)
              }
            ],
            unread: st.open ? st.unread : st.unread + 1
          }
        })
        break
      }

      case 'human_close':
        set((st) => ({
          human: st.human ? { ...st.human, status: 'closed', statusName: '已结束' } : st.human,
          statusText: str(evt.text) || '本次会话已结束'
        }))
        break

      // `human_offline_tip`：**刻意不处理**。它在 C4 §4.7 的白名单里，但全库无人发出
      // （REQ-20260910 §12 A5）。真正的"无人在线"信号是 `request()` 响应里的 `promptLeave`。
      // 为一个永不触发的事件写分支，等于写一段永远跑不到的代码。

      default:
        console.warn('[ai-support] 买家人工通道未知事件类型（已忽略）：', evt.type)
    }
  },

  reset() {
    inflight?.controller.abort()
    inflight = null
    set({
      open: false,
      unread: 0,
      messages: [],
      sending: false,
      sendingHuman: false,
      transferring: false,
      human: null,
      snapshotLoaded: false,
      snapshotFailed: false,
      statusText: null
    })
  }
}))
