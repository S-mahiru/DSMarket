import { message } from 'antd'
import router from '@/router'
import { API_BASE, getToken, removeToken } from '@/api/request'

/**
 * SSE 传输层 —— AI 客服三条流（/ai/chat、/ai/support/stream、/admin/ai/support/stream）的共同底座。
 *
 * **为什么不用原生 `EventSource`**：`JwtAuthenticationFilter` 只读 `Authorization` 头，没有 query 参数通道，
 * 而 `EventSource` 不能自定义请求头 → 带不上 JWT（把 token 塞进 URL 会进访问日志，已否决）。
 * 且 `/ai/chat` 是 POST 带 body，`EventSource` 本来也做不到。
 *
 * **为什么不走 axios 实例**：`request.ts` 的 `timeout: 15000` 会掐断长生成，且 axios 会缓冲整个响应体
 * ——流式就没了。代价是绕过了拦截器，所以拦截器里"绕过就没了"的那部分行为必须在这里复刻，
 * 见 `handleStreamFailure()`。
 *
 * **后端帧格式（读了 `SseChatStream` / `SseSupportStreams` 确认）**：
 * - 三条流的 `type` 都在 `data:` 的 **JSON 里**，SSE 的 `event:` 行不用 → 统一按 `data.type` 分派即可；
 * - 心跳是**注释帧** `: ping`（每 15s），按规范不产生任何事件 —— 这里必须丢掉它，
 *   否则空闲期会被当成"收到了一条空事件"。
 */

/** 一条流事件。`type` 一定在，其余字段按事件类型各自解释（三条流的 `type` 都在 data 的 JSON 里）。 */
export interface SseEvent {
  type: string
  [k: string]: unknown
}

/**
 * 把一帧 `data:` 的原始文本解析成事件。三条流（chat / 买家人工 / 坐席）共用，**只有这一处落点**。
 *
 * 坏帧只丢这一帧、不拖垮整条流；但要留痕 —— 否则"某个事件永远收不到"会查无实据。
 * `name` 只进日志，用来分辨是哪条流。
 */
export function parseSseEvent(raw: string, name: string): SseEvent | null {
  let evt: unknown
  try {
    evt = JSON.parse(raw)
  } catch {
    console.warn(`[ai-stream:${name}] 帧不是合法 JSON，已丢弃：`, raw)
    return null
  }
  if (!evt || typeof (evt as SseEvent).type !== 'string') {
    console.warn(`[ai-stream:${name}] 帧缺少 type，已丢弃：`, evt)
    return null
  }
  return evt as SseEvent
}

/** 开流前的失败（`GlobalExceptionHandler` 统一 `{code,message,data}` 信封） */
export class SseHttpError extends Error {
  readonly status: number
  readonly code: number | string | null

  constructor(status: number, code: number | string | null, msg: string) {
    super(msg)
    this.name = 'SseHttpError'
    this.status = status
    this.code = code
  }
}

/**
 * 增量帧解析器。
 *
 * 三件事必须做对，否则都会静默出错：
 * 1. **分帧**：SSE 的帧分隔是空行，可能是 `\n\n` 也可能是 `\r\n\r\n`。必须优先按 `\r\n\r\n` 匹配，
 *    否则 `data:x\r\n\r\n` 会先被 `\r\n` 规则切出一个空的"帧"，多吐一个事件。
 * 2. **跨 chunk**：`chunk` 可能切在帧中间，也可能切在分隔符中间（`\r\n\r` + `\n`）→ 必须累积。
 * 3. **注释帧**：以 `:` 开头的行（`: ping`）不是数据，整帧没有 `data:` 行时**不得**产生回调。
 */
export function createSseParser(onData: (data: string) => void) {
  // 非全局正则：`exec` 不带 lastIndex 状态，可安全反复调用
  const FRAME_SEP = /\r\n\r\n|\n\n|\r\r/
  let buf = ''

  function emit(rawFrame: string) {
    const lines = rawFrame.split(/\r\n|\n|\r/)
    const dataLines: string[] = []
    for (const line of lines) {
      if (!line || line.startsWith(':')) continue // 空行 / 注释（含 `: ping` 心跳）→ 丢弃
      if (!line.startsWith('data:')) continue // event:/id:/retry: 一律不用（type 在 data 的 JSON 里）
      const v = line.slice(5)
      dataLines.push(v.startsWith(' ') ? v.slice(1) : v)
    }
    // 规范：整帧没有任何 data 行则不派发事件 —— 这正是 `: ping` 不产生幽灵消息的原因
    if (dataLines.length > 0) onData(dataLines.join('\n'))
  }

  return {
    push(chunk: string) {
      buf += chunk
      for (;;) {
        const m = FRAME_SEP.exec(buf)
        if (!m) break
        const frame = buf.slice(0, m.index)
        buf = buf.slice(m.index + m[0].length)
        emit(frame)
      }
    },
    /** 流结束时收尾：`\r\n\r\n` 结尾的最后一帧已在 push 里派发；这里只兜"末尾没有空行"的情况 */
    flush() {
      const rest = buf
      buf = ''
      if (rest.includes('data:')) emit(rest)
    }
  }
}

export interface SseRequest {
  /** 相对 API_BASE 的路径，如 `/ai/support/stream` */
  path: string
  method?: 'GET' | 'POST'
  body?: unknown
  signal: AbortSignal
  onData: (data: string) => void
  /** 拿到 2xx 响应头时回调一次（不是"第一个字节到达"） */
  onOpen?: () => void
}

/**
 * 消费一条 SSE 流，直到：
 * - 流自然结束（**注意：服务端每 30min 必关一次连接，这是 `SseEmitter` 的绝对寿命不是空闲超时
 *   —— 见 `AiProperties.streamTimeout`。EOF 只代表"这一条连接结束了"，不代表会话结束**）；
 * - 或 `signal` 被 abort（此时**正常返回**，不抛错：主动断开不是异常）。
 *
 * 开流前的非 2xx 抛 `SseHttpError`；流中途的网络错误原样抛出，重连策略由调用方（aiStreams.ts）决定。
 */
export async function consumeSse(req: SseRequest): Promise<void> {
  const token = getToken()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (req.body !== undefined) headers['Content-Type'] = 'application/json'

  const res = await fetch(`${API_BASE}${req.path}`, {
    method: req.method ?? 'GET',
    headers,
    body: req.body === undefined ? undefined : JSON.stringify(req.body),
    signal: req.signal
  })

  if (!res.ok) {
    throw await toSseHttpError(res)
  }
  if (!res.body) {
    throw new SseHttpError(res.status, null, '响应无 body（不是 SSE 流）')
  }

  req.onOpen?.()

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = createSseParser(req.onData)
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      // `{stream:true}` 不能省：中文 delta 会被切在多字节中间，漏了这个 flag 会得到 U+FFFD 并永久污染气泡
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode()) // 冲掉解码器里残留的半个字符
    parser.flush()
  } catch (e) {
    // 主动 abort 时 read() 会以 AbortError 拒绝 —— 这是正常收尾，不是故障
    if (req.signal.aborted) return
    throw e
  } finally {
    reader.releaseLock()
  }
}

async function toSseHttpError(res: Response): Promise<SseHttpError> {
  let code: number | string | null = null
  let msg = `请求失败（${res.status}）`
  try {
    const text = await res.text()
    if (text) {
      const body = JSON.parse(text)
      code = body?.code ?? null
      msg = body?.message || msg
    }
  } catch {
    // 非 JSON（含空 body）→ 保留状态码兜底文案。注意：**开流之后的** BusinessException 无响应体
    // （GlobalExceptionHandler 对 isStreaming 走 noBody），所以那种情况连 4xx 都拿不到，
    // 表现为"EOF 而没有 done"——那是错误，不是完成，由上层判。
  }
  return new SseHttpError(res.status, code, msg)
}

/**
 * 流层失败的统一收尾 —— **复刻 `request.ts` 拦截器里被本层绕过的那部分**。
 *
 * 返回 `true` 表示"重试也没用"，调用方必须**停止重连**。不返回 true 的（429/5xx/网络抖动）
 * 交给调用方按退避重试。
 *
 * 不这么做的话，token 过期会变成一个**静默的重连死循环**：每次重连都 401，没人告诉用户，
 * 也没人清 token，流量一直打。
 */
export function handleStreamFailure(e: unknown): boolean {
  if (!(e instanceof SseHttpError)) return false
  if (e.status === 401) {
    removeToken()
    router.navigate('/login')
    return true
  }
  if (e.status === 403) {
    message.error('无权限访问')
    return true
  }
  return false
}
