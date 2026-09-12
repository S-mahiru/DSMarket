/**
 * 工作台展示用的格式化与文案映射。列表和详情都要用，放一处 ——
 * 免得两个地方各写一遍，慢慢就写出两种说法。
 */

/**
 * 短时间显示。
 *
 * 后端 `LocalDateTime` 序列化成 `2026-09-10T21:57:29`（ISO **无时区**），
 * 交给 `new Date(...)` 会按浏览器本地时区当成 UTC 再偏移一次，显示的时间凭空差几小时。
 * **所以这里一律截字符串**，不解析成 Date，也不引 dayjs（本项目没有这个直接依赖）。
 */
export function formatShortTime(v?: string | null): string {
  if (!v) return ''
  return v.length >= 16 ? `${v.slice(5, 10)} ${v.slice(11, 16)}` : v
}

/**
 * `origin` 的中文名。
 *
 * **后端只发枚举值、不发中文名**（`AdminSupportSessionVO` 没有 `originName` 字段），
 * 而 `AiSupportOrigin` 里明明带着 `displayName` —— 这里镜像的就是那三个词，用词与后端逐字一致，
 * 将来接口补上 `originName` 时不会冒出第二种说法。
 */
export const ORIGIN_LABELS: Record<string, string> = {
  USER_REQUEST: '用户主动',
  ANCHOR_HIT: '锚点命中',
  AI_SUGGEST: '建议点击'
}

export function originLabel(origin: string): string {
  return ORIGIN_LABELS[origin] ?? origin
}
