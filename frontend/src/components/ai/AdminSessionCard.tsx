import { Badge, Tag } from 'antd'
import type { AdminSessionSummary } from '@/types/ai'
import { formatShortTime, originLabel } from './format'
import './AdminSessionCard.scss'

interface Props {
  session: AdminSessionSummary
  selected: boolean
  onSelect: (id: number) => void
}

export default function AdminSessionCard({ session, selected, onSelect }: Props) {
  return (
    <button
      type="button"
      className={`ai-card${selected ? ' is-selected' : ''}`}
      onClick={() => onSelect(session.sessionId)}
    >
      <div className="ai-card__top">
        <span className="ai-card__user">{session.userMasked || `会话#${session.sessionId}`}</span>
        <Badge count={session.unreadCount} size="small" />
      </div>
      <div className="ai-card__meta">
        <Tag>{originLabel(session.origin)}</Tag>
        <span className="ai-card__time">{formatShortTime(session.lastMsgAt || session.requestedAt)}</span>
      </div>
    </button>
  )
}
