import { useEffect, useRef } from 'react'
import AiMessageBubble from './AiMessageBubble'
import type { ChatMessage } from '@/types/ai'
import './AiMessageList.scss'

interface Props {
  messages: ChatMessage[]
  onTransfer: () => void
  onQuickAsk: (text: string) => void
}

/** 空态快捷问法：点一下即发送（不是"替你打字"——省掉一次点击才是它存在的意义） */
const QUICK_ASKS = ['怎么申请退货？', '运费怎么计算？', '订单什么时候发货？']

export default function AiMessageList({ messages, onTransfer, onQuickAsk }: Props) {
  const boxRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  /** 用户是否还停在底部。往上翻历史时不该被新的 delta 拽回去 */
  const stickToBottom = useRef(true)

  function handleScroll() {
    const el = boxRef.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48
  }

  // 没有依赖数组：流式期间每个 delta 都会重渲染，这里就是"跟着内容走"的落点
  useEffect(() => {
    if (stickToBottom.current) endRef.current?.scrollIntoView({ block: 'end' })
  })

  return (
    <div className="ai-msg-list" ref={boxRef} onScroll={handleScroll}>
      {messages.length === 0 ? (
        <div className="ai-msg-list__empty">
          <p>您好，我是黑海商城智能客服，请问有什么可以帮您？</p>
          <div className="ai-msg-list__quick">
            {QUICK_ASKS.map((q) => (
              <button type="button" key={q} onClick={() => onQuickAsk(q)}>
                {q}
              </button>
            ))}
          </div>
        </div>
      ) : (
        messages.map((m) => <AiMessageBubble key={m.id} msg={m} onTransfer={onTransfer} />)
      )}
      <div ref={endRef} />
    </div>
  )
}
