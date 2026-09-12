import { consumeSse, handleStreamFailure, parseSseEvent } from '@/api/sse'
import type { SseEvent } from '@/api/sse'

export type { SseEvent }

/**
 * 常驻 SSE 连接的生命周期管理（买家人工通道 + 坐席工作台通道）。
 *
 * **为什么是模块级单例，而不是每个组件一条连接**（三条理由，缺一条都会出真 bug）：
 * 1. 这两条通道是**常驻**语义，且服务端**不重放历史**——挂在抽屉组件上，抽屉一关就丢掉那段时间的
 *    `human_close` / 坐席回复；
 * 2. 后端按用户维护**连接集合**并广播（`Map<Long, Set<SseEmitter>>`），React 19 StrictMode 的双挂载
 *    会让同一事件收到两遍；
 * 3. 必须**在 React 之外可达**：`useUserStore.logout()` 是个 zustand action，那一刻没有组件、没有路由变化。
 *
 * **`SseEmitter` 超时是"绝对寿命"不是"空闲超时"**（`AiProperties.streamTimeout = 30min`，
 * `/chat` 是 `sseTimeout = 3min`），15s 一次的 `: ping` 只保代理、**不续期**。所以
 * **两条常驻通道每 30 分钟必被服务端干净关一次，EOF ≠ 会话结束**——这里的重连不是"容错"，是常规路径。
 */

export type StreamStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'stopped'

/** 组件卸载后不立刻断连的宽限期：吸收 StrictMode 的「挂载→卸载→挂载」同步循环 */
const GRACE_MS = 500
/** 连接活不过这个时长就算"对面开不起来"，要退避；活得比它久还断，说明是 30min 到期这类正常收尾 */
const QUICK_FAIL_MS = 3000
const BACKOFF_MAX_MS = 15000

export interface AiStream {
  /** 引用计数 +1，返回释放函数（必须调用，否则连接永不断） */
  retain(): () => void
  /** 订阅已解析事件 */
  subscribe(fn: (evt: SseEvent) => void): () => void
  /** **每次（重）连成功都回调** —— 调用方在这里重拉快照，这是补齐断连期数据丢失的唯一手段 */
  onConnected(fn: () => void): () => void
  /** 连接态变化（供 UI 显示"重连中"） */
  onStatus(fn: (s: StreamStatus) => void): () => void
  /** 彻底停掉并复位（登出用）。之后再次 `retain()` 可重新启动 */
  reset(): void
}

function createAiStream(path: string, name: string): AiStream {
  const events = new Set<(evt: SseEvent) => void>()
  const connected = new Set<() => void>()
  const statuses = new Set<(s: StreamStatus) => void>()

  let refs = 0
  let running = false
  let status: StreamStatus = 'idle'
  let controller: AbortController | null = null
  let graceTimer: ReturnType<typeof setTimeout> | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let retryResolve: (() => void) | null = null
  let backoff = 0

  function setStatus(s: StreamStatus) {
    if (status === s) return
    status = s
    statuses.forEach((fn) => fn(s))
  }

  function dispatch(raw: string) {
    // 解析与 ai.ts 的 /chat 共用同一份实现（`parseSseEvent`）：三条流的帧格式是同一个协议，
    // 分开写迟早只改一边。
    const evt = parseSseEvent(raw, name)
    if (!evt) return
    events.forEach((fn) => fn(evt))
  }

  /** 可被 `stop()` 提前唤醒的 sleep —— 不然停流后循环还挂着一个定时器等它自然到期 */
  function sleep(ms: number) {
    return new Promise<void>((resolve) => {
      retryResolve = resolve
      retryTimer = setTimeout(() => {
        retryTimer = null
        retryResolve = null
        resolve()
      }, ms)
    })
  }

  function wake() {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    const r = retryResolve
    retryResolve = null
    r?.()
  }

  async function loop() {
    while (running) {
      controller = new AbortController()
      const openedAt = Date.now()
      setStatus('connecting')
      try {
        await consumeSse({
          path,
          signal: controller.signal,
          onData: dispatch,
          onOpen: () => {
            backoff = 0 // 连上了就清零：下一次断线该立刻重连
            setStatus('open')
            connected.forEach((fn) => fn())
          }
        })
        // 走到这里 = 干净 EOF（不是异常）。可能是 30min 绝对超时，也可能是服务端主动关。
        if (!running) break
        if (Date.now() - openedAt < QUICK_FAIL_MS) backoff = nextBackoff(backoff)
        else backoff = 0 // 活得够久 → 当作正常收尾，立刻重连
        setStatus('reconnecting')
      } catch (e) {
        if (!running) break
        if (handleStreamFailure(e)) {
          // 401/403：重试一万次也还是 401。清 token + 跳登录已由 sse.ts 做掉，这里只负责**别再重连**，
          // 否则过期 token 会变成一个没人知道的静默重连死循环。
          console.warn(`[ai-stream:${name}] 鉴权失败，停止重连`)
          stop()
          break
        }
        backoff = nextBackoff(backoff)
        setStatus('reconnecting')
      } finally {
        controller = null
      }
      if (!running) break
      await sleep(backoff)
    }
    setStatus('stopped')
  }

  function start() {
    if (running) return
    running = true
    backoff = 0
    void loop()
  }

  function stop() {
    running = false
    wake()
    controller?.abort()
    controller = null
    setStatus('stopped')
  }

  return {
    retain() {
      refs++
      if (graceTimer) {
        clearTimeout(graceTimer)
        graceTimer = null
      }
      start()
      let released = false
      return () => {
        if (released) return
        released = true
        refs--
        if (refs > 0) return
        graceTimer = setTimeout(() => {
          graceTimer = null
          if (refs <= 0) stop()
        }, GRACE_MS)
      }
    },
    subscribe(fn) {
      events.add(fn)
      return () => events.delete(fn)
    },
    onConnected(fn) {
      connected.add(fn)
      return () => connected.delete(fn)
    },
    onStatus(fn) {
      statuses.add(fn)
      return () => statuses.delete(fn)
    },
    reset() {
      if (graceTimer) {
        clearTimeout(graceTimer)
        graceTimer = null
      }
      refs = 0
      stop()
      events.clear()
      connected.clear()
      statuses.clear()
      status = 'idle'
    }
  }
}

function nextBackoff(cur: number) {
  return cur === 0 ? 500 : Math.min(BACKOFF_MAX_MS, cur * 2)
}

/** 买家侧人工通道（C4 §4.7） */
export const buyerStream = createAiStream('/ai/support/stream', 'buyer')

/** 坐席工作台通道（C4 §4.8） */
export const seatStream = createAiStream('/admin/ai/support/stream', 'seat')

/**
 * 登出时调用（`stores/user.ts` 的 `logout()`）。
 *
 * 必须挂在这里而不是"路由变化"上：`MainLayout` 是**裸调** `logout()` 的，`logout()` 自己也不导航，
 * 所以按路由断流的话，登出后连接会带着已经失效的 token 继续活着。
 */
export function resetAiStreams() {
  buyerStream.reset()
  seatStream.reset()
}
