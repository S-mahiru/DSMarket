import { useEffect, useRef, useState } from 'react'
import { Button, Empty, Input, Popconfirm, Spin, Tag } from 'antd'
import { CloseCircleOutlined, LoginOutlined } from '@ant-design/icons'
import { useAdminAiSupportStore } from '@/stores/adminAiSupport'
import AdminAiSummary from './AdminAiSummary'
import { formatShortTime, originLabel } from './format'
import './AdminSessionDetail.scss'

/** 坐席回复上限（C4 §3.2）：与后端 `AgentReplyRequest @Size(max = 4000)` 对齐 */
const REPLY_MAX = 4000

const SENDER_LABELS: Record<string, string> = { USER: '买家', AGENT: '客服', SYSTEM: '系统' }

/** 会话状态 → Tag 颜色。**文案一律用服务端的 `statusName`**，这里只管颜色不问措辞。 */
const STATUS_COLORS: Record<string, string> = {
  pending_human: 'orange',
  human_active: 'green',
  message_left: 'purple',
  closed: 'default'
}

/**
 * 工作台右侧详情（C4 §4.5）：消息 + AI 回放 + 接入/回复/结束。
 *
 * 三个按钮的可用条件**都来自服务端状态**，不是本地开关：
 * - 接入：仅 `pending_human`（CAS 保证并发双接入只有一个赢，落败方 409）
 * - 结束：任何非 `closed`（PH 三态都能结束，H26）
 * - 回复：`human_active || message_left` —— **不是**只看 `human_active`。
 *   C4 的 P2 决议：坐席对留言的回复不自动关会话，所以"已留言"的会话是可回复的（H25）。
 *   未接入（`pending_human`）回复会被服务端 409 挡掉：那会造出"没人接过、却已有坐席发言"的会话。
 */
export default function AdminSessionDetail() {
  const detail = useAdminAiSupportStore((s) => s.detail)
  const detailLoading = useAdminAiSupportStore((s) => s.detailLoading)
  const taking = useAdminAiSupportStore((s) => s.taking)
  const closing = useAdminAiSupportStore((s) => s.closing)
  const replying = useAdminAiSupportStore((s) => s.replying)
  const take = useAdminAiSupportStore((s) => s.take)
  const close = useAdminAiSupportStore((s) => s.close)
  const reply = useAdminAiSupportStore((s) => s.reply)

  const [text, setText] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  const sid = detail?.sessionId ?? null

  // 切会话就清草稿：把上一个买家没发出去的话顺手带给下一个，是最糟的一种"贴心"
  useEffect(() => {
    setText('')
  }, [sid])

  // 详情是"当前正在处理的会话"，新消息到达后直接贴底，不做"用户上翻就锁住"那套
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [detail?.messages.length])

  if (!detail) {
    return (
      <div className="ai-detail ai-detail--empty">
        <Empty description="从左侧选择一条会话" />
      </div>
    )
  }

  const isClosed = detail.status === 'closed'
  const canReply = detail.status === 'human_active' || detail.status === 'message_left'

  async function send() {
    if (!text.trim()) return
    const ok = await reply(text)
    // 失败时**保留草稿**：409 说明会话已结束或还没接入，内容还在输入框里，坐席能复制走
    if (ok) setText('')
  }

  return (
    <div className="ai-detail">
      <header className="ai-detail__head">
        <div className="ai-detail__who">
          <span className="ai-detail__user">{detail.userMasked || `会话#${detail.sessionId}`}</span>
          <Tag color={STATUS_COLORS[detail.status] ?? 'default'}>{detail.statusName}</Tag>
          <span className="ai-detail__origin">来源：{originLabel(detail.origin)}</span>
        </div>
        <div className="ai-detail__actions">
          {detail.status === 'pending_human' ? (
            <Button
              size="small"
              type="primary"
              icon={<LoginOutlined />}
              loading={taking}
              onClick={() => void take(detail.sessionId)}
            >
              接入
            </Button>
          ) : null}
          {!isClosed ? (
            <Popconfirm
              title="结束本次人工会话？"
              description="买家会收到会话结束通知，之后不能再在本会话留言；重新咨询会开新会话。"
              okText="结束"
              cancelText="取消"
              onConfirm={() => void close(detail.sessionId)}
            >
              <Button size="small" danger icon={<CloseCircleOutlined />} loading={closing}>
                结束会话
              </Button>
            </Popconfirm>
          ) : null}
        </div>
      </header>

      <div className="ai-detail__msgs" ref={listRef}>
        {detailLoading ? (
          <div className="ai-detail__loading">
            <Spin />
          </div>
        ) : detail.messages.length === 0 ? (
          <Empty description="还没有消息" />
        ) : (
          detail.messages.map((m) => (
            <div
              key={m.messageId}
              className={`ai-msg ai-msg--${m.sender.toLowerCase()}${m.pending ? ' is-pending' : ''}`}
            >
              <div className="ai-msg__head">
                <span className="ai-msg__sender">{SENDER_LABELS[m.sender] ?? m.sender}</span>
                <span className="ai-msg__time">{formatShortTime(m.createdAt)}</span>
                {m.pending ? <span className="ai-msg__pending">发送中…</span> : null}
              </div>
              <p className="ai-msg__body">{m.content}</p>
            </div>
          ))
        )}
      </div>

      <section className="ai-detail__ai">
        <h4 className="ai-detail__ai-title">转人工前的 AI 对话</h4>
        <AdminAiSummary items={detail.aiSummary ?? []} />
      </section>

      <div className="ai-detail__composer">
        {isClosed ? (
          <p className="ai-detail__notice">本次人工会话已结束，无法回复。</p>
        ) : !canReply ? (
          <p className="ai-detail__notice">
            请先点「接入」再回复。未接入就回复会被服务端拒绝 —— 那会造出"没人接过、却已经有坐席发言"的会话。
          </p>
        ) : (
          <>
            <Input.TextArea
              value={text}
              onChange={(e) => setText(e.target.value)}
              maxLength={REPLY_MAX}
              autoSize={{ minRows: 3, maxRows: 6 }}
              placeholder="输入回复内容，回车发送，Shift + 回车换行"
              onPressEnter={(e) => {
                if (!e.shiftKey && !e.ctrlKey) {
                  e.preventDefault()
                  void send()
                }
              }}
            />
            <div className="ai-detail__send">
              <span className="ai-detail__count">
                {text.length}/{REPLY_MAX}
              </span>
              <Button type="primary" loading={replying} disabled={!text.trim()} onClick={() => void send()}>
                发送
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
