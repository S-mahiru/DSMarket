import AdminSessionCard from './AdminSessionCard'
import type { AdminSessionSummary } from '@/types/ai'
import './AdminSessionColumn.scss'

interface Props {
  title: string
  tone: 'queued' | 'active' | 'left'
  sessions: AdminSessionSummary[]
  selectedId: number | null
  onSelect: (id: number) => void
}

/**
 * 工作台的一列（C4 §4.5 三列表）。
 *
 * 空列表显示"暂无会话" —— 调用方必须先确认快照拉成功过（`boardLoaded`）才渲染本组件，
 * 否则"没拉到"会被显示成"没有会话"。
 */
export default function AdminSessionColumn({ title, tone, sessions, selectedId, onSelect }: Props) {
  return (
    <section className={`ai-col ai-col--${tone}`}>
      <header className="ai-col__head">
        <span>{title}</span>
        <span className="ai-col__count">{sessions.length}</span>
      </header>
      <div className="ai-col__body">
        {sessions.length === 0 ? (
          <p className="ai-col__empty">暂无会话</p>
        ) : (
          sessions.map((s) => (
            <AdminSessionCard
              key={s.sessionId}
              session={s}
              selected={s.sessionId === selectedId}
              onSelect={onSelect}
            />
          ))
        )}
      </div>
    </section>
  )
}
