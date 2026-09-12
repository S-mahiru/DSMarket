import { Tag } from 'antd'
import { LoadingOutlined, UserSwitchOutlined } from '@ant-design/icons'
import { useAiSupportStore } from '@/stores/aiSupport'
import { REPLY_ID_HUMAN_ROUTED } from '@/types/ai'
import type { ChatMessage } from '@/types/ai'
import AiFeedbackButtons from './AiFeedbackButtons'
import './AiMessageBubble.scss'

interface Props {
  msg: ChatMessage
  onTransfer: () => void
}

export default function AiMessageBubble({ msg, onTransfer }: Props) {
  const submitFeedback = useAiSupportStore((s) => s.submitFeedback)

  // `human-routed` 是哨兵、不是一次真实轮次（后端没有这一轮的会话记录）→ **不给赞踩**。
  // 正常情况下这种气泡在前端已被整轮丢弃（见 store 的收尾分类），这里再挡一次是因为
  // 它是**协议级**约束，不该只依赖上游一处判断。
  const canFeedback =
    msg.channel === 'ai' &&
    msg.role === 'assistant' &&
    msg.turn === 'done' &&
    !!msg.replyId &&
    msg.replyId !== REPLY_ID_HUMAN_ROUTED

  return (
    <div className={`ai-msg ai-msg--${msg.role}`}>
      {msg.tools && msg.tools.length > 0 ? (
        <div className="ai-msg__tools">
          {msg.tools.map((label, i) => (
            // 文案是服务端下发的（`tool_begin.label`），前端不得自编
            <Tag key={`${label}-${i}`} icon={<LoadingOutlined />} color="processing">
              {label}
            </Tag>
          ))}
        </div>
      ) : null}

      <div className="ai-msg__bubble">
        {msg.content}
        {msg.turn === 'streaming' ? <span className="ai-msg__caret" /> : null}
      </div>

      {msg.fallback ? <div className="ai-msg__badge">（自动回复）</div> : null}

      {msg.suggestReason ? (
        <button type="button" className="ai-msg__suggest" onClick={onTransfer}>
          <UserSwitchOutlined /> 转人工客服
        </button>
      ) : null}

      {msg.turn === 'error' ? <div className="ai-msg__error">{msg.errorText ?? '生成失败'}</div> : null}
      {/* 「用户停止」与「转人工打断」都**不是错误**，服务端对两者都不补 done（C1 E10/E11） */}
      {msg.turn === 'stopped' ? <div className="ai-msg__note">已停止</div> : null}
      {msg.turn === 'interrupted' ? <div className="ai-msg__note">已中断</div> : null}
      {msg.failed ? <div className="ai-msg__error">发送失败</div> : null}

      {canFeedback ? (
        <AiFeedbackButtons
          value={msg.feedback}
          onFeedback={(satisfied) => void submitFeedback(msg.id, satisfied)}
        />
      ) : null}
    </div>
  )
}
