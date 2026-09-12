import type { AiSummaryItem } from '@/types/ai'
import './AdminAiSummary.scss'

interface Props {
  items: AiSummaryItem[]
}

/**
 * AI 对话回放（C4 §4.5 L 决议）：从 Redis 取最近 N 轮的**原文直读**，不生成摘要、不调模型。
 *
 * 空列表的语义是**"取不到"**（会话已过期，或转人工前压根没聊过），不是"没有 AI 对话" ——
 * 所以文案要写清楚是哪种取不到，也不能拿本会话的消息去"补"出一段回放来（那是编造）。
 */
export default function AdminAiSummary({ items }: Props) {
  if (items.length === 0) {
    return (
      <p className="ai-summary__empty">无 AI 对话记录（转人工前无近期 AI 问答，或会话已过期）</p>
    )
  }

  return (
    <div className="ai-summary">
      {items.map((it, i) => (
        // ts 是 epoch millis，同一毫秒可能有多轮 —— 拼上下标保证 key 唯一
        <div className="ai-summary__turn" key={`${it.ts}-${i}`}>
          <p className="ai-summary__q">
            <span className="ai-summary__who">用户</span>
            {it.userContent}
          </p>
          <p className="ai-summary__a">
            <span className="ai-summary__who">AI</span>
            {it.assistantContent}
          </p>
        </div>
      ))}
    </div>
  )
}
