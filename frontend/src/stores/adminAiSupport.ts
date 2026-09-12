import { create } from 'zustand'
import {
  closeSession,
  fetchBoard,
  fetchDetail,
  markSessionRead,
  replySession,
  sendHeartbeat,
  seatLogout,
  takeSession
} from '@/api/admin/aiSupport'
import type { SseEvent } from '@/api/sse'
import type {
  AdminBoard,
  AdminMessage,
  AdminSessionDetail,
  AdminSessionSummary,
  HumanStatus,
  SeatState,
  SupportSender
} from '@/types/ai'

/**
 * 坐席工作台的状态（C4 §4.5 三列表 + §4.8 admin 通道）。
 *
 * 三条硬规则落在本文件里，别处不要重写一遍：
 *
 * 1. **快照是权威**。`GET /sessions` 是看板唯一的历史来源，SSE 只推增量、不重放，
 *    所以每次（重）连都要重拉（§4.8 断线重连原则①）。而 30 分钟绝对超时是**常态**，
 *    这条路径天天走，不是容错分支。
 * 2. **`message_new` 必须去重**。服务端对**坐席自己发的回复也推**这条事件（为多标签同步），
 *    不去重就会把自己刚发的那条渲染两遍。去重键 `(sessionId, eventId)` 里的 eventId
 *    就是消息主键，所以落到这里就是"详情里按 messageId 去重"。
 * 3. **乐观占位的两个到达顺序都要能收掉**：先拿到 POST 响应、或先收到 SSE 事件，
 *    都必须把占位替换成真消息而不是多出一条。占位 id 取**负数**（`-Date.now()`），
 *    保证永不与自增主键相撞。
 */

const EMPTY_BOARD: AdminBoard = { queued: [], active: [], left: [] }

/** 心跳间隔（§4.2）：15s 一次，刷新服务端 45s TTL 的在线键 */
const HEARTBEAT_MS = 15000
/** 离线判定窗口：服务端**不发** heartbeat_timeout，超时只能前端自判 */
const OFFLINE_AFTER_MS = 45000
/** 看门狗检查间隔 */
const WATCHDOG_MS = 5000
/** 离开工作台后等这么久再上报离线：吸收 StrictMode 的「挂载→卸载→挂载」同步循环 */
const LEAVE_GRACE_MS = 500

/**
 * 状态迁移事件只带 `status`、**不带 `statusName`**（见 `SupportEvents.sessionStatus`），
 * 而列表卡片要显示中文名。这里镜像 `AiSupportSessionStatus.displayName` 的四个词，
 * **只用于事件到达那一瞬间的渲染** —— 下一次拉快照就会被服务端的权威值覆盖。
 */
const STATUS_NAMES: Record<HumanStatus, string> = {
  pending_human: '等待接入',
  human_active: '人工处理中',
  message_left: '已留言',
  closed: '已结束'
}

let beatTimer: ReturnType<typeof setInterval> | null = null
let watchdogTimer: ReturnType<typeof setInterval> | null = null
let leaveTimer: ReturnType<typeof setTimeout> | null = null

function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

function findSession(board: AdminBoard, id: number): AdminSessionSummary | undefined {
  return [...board.queued, ...board.active, ...board.left].find((s) => s.sessionId === id)
}

/** 从三列表里摘掉某个会话（不关心它在哪一列） */
function dropSession(board: AdminBoard, id: number): AdminBoard {
  return {
    queued: board.queued.filter((s) => s.sessionId !== id),
    active: board.active.filter((s) => s.sessionId !== id),
    left: board.left.filter((s) => s.sessionId !== id)
  }
}

/** 把会话挪到 `status` 对应的列。**`closed` 不在三列表里**（`PH_VALUES` 不含它）→ 直接摘掉。 */
function moveSession(
  board: AdminBoard,
  id: number,
  status: HumanStatus,
  patch: Partial<AdminSessionSummary>
): AdminBoard {
  if (status === 'closed') return dropSession(board, id)
  const cur = findSession(board, id)
  if (!cur) return board
  const next = dropSession(board, id)
  const item: AdminSessionSummary = { ...cur, ...patch, status, statusName: STATUS_NAMES[status] }
  if (status === 'pending_human') next.queued = [item, ...next.queued]
  else if (status === 'human_active') next.active = [item, ...next.active]
  else next.left = [item, ...next.left]
  return next
}

/** 刷新某会话的 `lastMsgAt`，并可选地给红点 +1 */
function touchSession(board: AdminBoard, id: number, lastMsgAt: string, bumpUnread: boolean): AdminBoard {
  const patch = (list: AdminSessionSummary[]) =>
    list.map((s) =>
      s.sessionId === id
        ? {
            ...s,
            lastMsgAt: lastMsgAt || s.lastMsgAt,
            unreadCount: bumpUnread ? s.unreadCount + 1 : s.unreadCount
          }
        : s
    )
  return { queued: patch(board.queued), active: patch(board.active), left: patch(board.left) }
}

function clearUnread(board: AdminBoard, id: number): AdminBoard {
  const patch = (list: AdminSessionSummary[]) =>
    list.map((s) => (s.sessionId === id ? { ...s, unreadCount: 0 } : s))
  return { queued: patch(board.queued), active: patch(board.active), left: patch(board.left) }
}

/**
 * 合并一条服务端消息。
 *
 * 去重判断放最前：事件与 POST 响应可能先后到达，**后到的那个必须被认出来并丢弃**，
 * 否则同一条回复会出现两次。
 */
function mergeMessage(list: AdminMessage[], msg: AdminMessage): AdminMessage[] {
  if (list.some((m) => m.messageId === msg.messageId)) return list
  // 自己的乐观占位优先被"认领"；只认领同 sender 的占位，免得把刚到的买家消息错当成自己的回复
  const i = list.findIndex((m) => m.pending && m.sender === msg.sender)
  if (i >= 0) {
    const next = list.slice()
    next[i] = msg
    return next
  }
  return [...list, msg]
}

interface AdminAiSupportState {
  board: AdminBoard
  /** 快照成功拉取过没有。**没成功就不能渲染"暂无会话"** —— 那是把"不知道"说成"没有" */
  boardLoaded: boolean
  boardLoading: boolean

  selectedId: number | null
  detail: AdminSessionDetail | null
  detailLoading: boolean

  seat: SeatState
  /** 最近一次心跳成功的本地时间戳（毫秒）。离线判定用它，**不用服务端事件**（没有那个事件） */
  lastBeatAt: number

  taking: boolean
  closing: boolean
  replying: boolean

  refreshBoard: () => Promise<void>
  selectSession: (id: number | null) => Promise<void>
  take: (id: number) => Promise<void>
  close: (id: number) => Promise<void>
  reply: (content: string) => Promise<boolean>
  handleSeatEvent: (evt: SseEvent) => void
  onStreamConnected: () => void
  startSeat: () => void
  stopSeat: () => void
  reset: () => void
}

export const useAdminAiSupportStore = create<AdminAiSupportState>((set, get) => {
  /** 心跳成功才推进 `lastBeatAt`；失败时**故意不动它**，让看门狗按超时如实判离线 */
  async function beat() {
    try {
      const res = await sendHeartbeat()
      set({ seat: 'online', lastBeatAt: Date.now() })
      if (res?.newlyOnline) void get().refreshBoard()
    } catch {
      // 单次心跳失败很常见（抖动、后端重启），拦截器已提示过。这里**不立刻标离线**：
      // 离线是"连续 45s 没成功"，交给看门狗判 —— 一次失败就翻脸会满屏假离线。
    }
  }

  return {
    board: EMPTY_BOARD,
    boardLoaded: false,
    boardLoading: false,
    selectedId: null,
    detail: null,
    detailLoading: false,
    seat: 'unknown',
    lastBeatAt: 0,
    taking: false,
    closing: false,
    replying: false,

    async refreshBoard() {
      set({ boardLoading: true })
      try {
        const b = await fetchBoard()
        set({
          board: { queued: b?.queued ?? [], active: b?.active ?? [], left: b?.left ?? [] },
          boardLoaded: true,
          boardLoading: false
        })
      } catch {
        // 拉不到就**不置 boardLoaded**：三列显示"加载失败 + 重试"，而不是假装"没有会话"
        set({ boardLoading: false })
      }
    },

    async selectSession(id) {
      if (id == null) {
        set({ selectedId: null, detail: null })
        return
      }
      set({ selectedId: id, detailLoading: true })
      try {
        const d = await fetchDetail(id)
        // 竞态：请求在飞时坐席可能已经点了别的会话 —— 晚到的响应不许覆盖新的选择
        if (get().selectedId !== id) return
        set({ detail: d, detailLoading: false })
        // 打开即标记已读（§4.5 决议 J：清红点、**不回推买家**）。
        // 这是清红点的唯一入口，所以不再单独放一个"标记已读"按钮 —— 它点与不点，
        // 语义上都等价于"你已经看过这条会话了"。
        if (d.status !== 'closed') {
          void markSessionRead(id)
            .then(() => set((st) => ({ board: clearUnread(st.board, id) })))
            .catch(() => {})
        }
      } catch {
        if (get().selectedId === id) set({ detailLoading: false })
      }
    },

    async take(id) {
      if (get().taking) return
      set({ taking: true })
      try {
        await takeSession(id)
      } catch {
        // 409 = 别的坐席抢先接入了 / 状态已变（H8）。**成功失败都要重新同步**：
        // 不重新拉，界面会一直停在"可以接入"，而服务端那边早就推进了。
      } finally {
        set({ taking: false })
        await get().refreshBoard()
        if (get().selectedId === id) await get().selectSession(id)
      }
    },

    async close(id) {
      if (get().closing) return
      set({ closing: true })
      try {
        await closeSession(id)
      } catch {
        // 409 = 该会话已经结束了。不当异常收场，照常重新同步即可
      } finally {
        set({ closing: false })
        await get().refreshBoard()
        // 结束后会话从三列表消失（closed 不是 PH 值），但**详情仍要刷新** ——
        // 坐席得看见"已结束"这个结果，而不是卡片凭空不见、详情还停在可回复
        if (get().selectedId === id) await get().selectSession(id)
      }
    },

    async reply(content) {
      const sid = get().selectedId
      const detail = get().detail
      const text = content.trim()
      if (sid == null || !detail || get().replying || !text) return false

      // 负数 id：保证永不与自增主键相撞，且能一眼认出"这条还没落地"
      const localId = -Date.now()
      const placeholder: AdminMessage = {
        messageId: localId,
        sender: 'AGENT',
        content: text,
        createdAt: new Date().toISOString(),
        readByAgent: true,
        pending: true
      }
      set((st) =>
        st.detail && st.detail.sessionId === sid
          ? { replying: true, detail: { ...st.detail, messages: [...st.detail.messages, placeholder] } }
          : { replying: true }
      )

      try {
        const vo = await replySession(sid, text)
        set((st) =>
          st.detail && st.detail.sessionId === sid
            ? {
                detail: {
                  ...st.detail,
                  messages: mergeMessage(st.detail.messages, { ...vo, readByAgent: true })
                }
              }
            : {}
        )
        return true
      } catch {
        // 409 REPLY_CLOSED / REPLY_NOT_TAKEN：**本条没有落库** → 撤掉占位。
        // 草稿由调用方保留（不在这里清输入框），内容还在，坐席能复制走 ——
        // 比"显示了却其实没发出去"诚实。
        set((st) =>
          st.detail && st.detail.sessionId === sid
            ? { detail: { ...st.detail, messages: st.detail.messages.filter((m) => m.messageId !== localId) } }
            : {}
        )
        return false
      } finally {
        set({ replying: false })
      }
    },

    handleSeatEvent(evt) {
      switch (evt.type) {
        case 'session_new': {
          const sessionId = num(evt.sessionId)
          if (sessionId == null) break
          set((st) =>
            findSession(st.board, sessionId)
              ? {}
              : {
                  board: {
                    ...st.board,
                    queued: [
                      {
                        sessionId,
                        userMasked: str(evt.userMasked),
                        origin: str(evt.origin),
                        status: 'pending_human',
                        statusName: STATUS_NAMES.pending_human,
                        requestedAt: str(evt.requestedAt),
                        lastMsgAt: str(evt.lastMsgAt),
                        unreadCount: 0
                      },
                      ...st.board.queued
                    ]
                  }
                }
          )
          break
        }

        case 'session_status': {
          const sessionId = num(evt.sessionId)
          const status = str(evt.status) as HumanStatus
          if (sessionId == null || !STATUS_NAMES[status]) break
          // 快照与 SSE 连接建立之间有窗口期，那期间发生的事两条都不会告诉我们。
          // 事件提到的会话我们本地没有 → 拉一次快照自愈，而不是把这条事件悄悄丢掉。
          if (!findSession(get().board, sessionId)) void get().refreshBoard()
          const patch: Partial<AdminSessionSummary> = { statusName: STATUS_NAMES[status] }
          if (str(evt.activeAt)) patch.activeAt = str(evt.activeAt)
          if (str(evt.closedAt)) patch.closedAt = str(evt.closedAt)
          set((st) => ({
            board: moveSession(st.board, sessionId, status, patch),
            // 详情面板同步状态：坐席自己结束、或别的坐席接入，都要立刻反映到按钮可用性上
            detail:
              st.detail && st.detail.sessionId === sessionId
                ? { ...st.detail, ...patch, status, statusName: STATUS_NAMES[status] }
                : st.detail
          }))
          break
        }

        case 'message_new': {
          const sessionId = num(evt.sessionId)
          const messageId = num(evt.eventId)
          if (sessionId == null || messageId == null) break
          const sender = str(evt.sender) as SupportSender
          const createdAt = str(evt.createdAt)
          const watching = get().selectedId === sessionId
          if (!findSession(get().board, sessionId)) void get().refreshBoard()
          const msg: AdminMessage = {
            messageId,
            sender,
            content: str(evt.content),
            createdAt,
            readByAgent: sender === 'USER' ? watching : true
          }
          set((st) => {
            const board = touchSession(st.board, sessionId, createdAt, sender === 'USER' && !watching)
            if (!watching || !st.detail || st.detail.sessionId !== sessionId) return { board }
            return { board, detail: { ...st.detail, messages: mergeMessage(st.detail.messages, msg) } }
          })
          // 正在看这条会话时来了新消息 → 顺手置已读，免得本地红点与服务端计数脱节
          if (watching && sender === 'USER') {
            void markSessionRead(sessionId).catch(() => {})
          }
          break
        }

        case 'seat_status':
          // 该事件只推给"自己"（`publisher.toAdmin(adminId, ...)`），所以收到的一定是本坐席的在线态
          set({ seat: str(evt.state) === 'online' ? 'online' : 'offline' })
          break

        default:
          console.warn('[ai-seat] 工作台通道未知事件类型（已忽略）：', evt.type)
      }
    },

    onStreamConnected() {
      // §4.8 原则①：重连后先拉快照再收增量
      void get().refreshBoard()
      const id = get().selectedId
      if (id != null) void get().selectSession(id)
    },

    startSeat() {
      // 重新进入工作台 → 撤销上一次离开时排的"上报离线"
      if (leaveTimer) {
        clearTimeout(leaveTimer)
        leaveTimer = null
      }
      if (beatTimer) return
      set({ lastBeatAt: Date.now() })
      void beat()
      beatTimer = setInterval(() => void beat(), HEARTBEAT_MS)
      watchdogTimer = setInterval(() => {
        if (Date.now() - get().lastBeatAt > OFFLINE_AFTER_MS) set({ seat: 'offline' })
      }, WATCHDOG_MS)
    },

    stopSeat() {
      if (beatTimer) {
        clearInterval(beatTimer)
        beatTimer = null
      }
      if (watchdogTimer) {
        clearInterval(watchdogTimer)
        watchdogTimer = null
      }
      if (leaveTimer) return
      // 宽限期后再上报离线。这条**不是可选的**：坐席切到别的页面后心跳就停了，
      // 而服务端在线键还能活 45s —— 那段时间买家会被"有人在线"骗着干等接入。
      leaveTimer = setTimeout(() => {
        leaveTimer = null
        set({ seat: 'offline' })
        void seatLogout().catch(() => {})
      }, LEAVE_GRACE_MS)
    },

    reset() {
      if (beatTimer) {
        clearInterval(beatTimer)
        beatTimer = null
      }
      if (watchdogTimer) {
        clearInterval(watchdogTimer)
        watchdogTimer = null
      }
      if (leaveTimer) {
        clearTimeout(leaveTimer)
        leaveTimer = null
      }
      set({
        board: EMPTY_BOARD,
        boardLoaded: false,
        boardLoading: false,
        selectedId: null,
        detail: null,
        detailLoading: false,
        seat: 'unknown',
        lastBeatAt: 0,
        taking: false,
        closing: false,
        replying: false
      })
    }
  }
})
