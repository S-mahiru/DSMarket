import { useEffect } from 'react'
import { Button, Drawer, Spin, Tag } from 'antd'
import { CustomerServiceOutlined, UserSwitchOutlined } from '@ant-design/icons'
import { useAiSupportStore } from '@/stores/aiSupport'
import { useUserStore } from '@/stores/user'
import { useNavigate } from 'react-router-dom'
import { useChatContextReader } from './useChatContext'
import AiMessageList from './AiMessageList'
import AiComposer from './AiComposer'
import './AiSupportDrawer.scss'

/**
 * 买家客服抽屉（REQ-20260910 §4.1 AI 态 + §4.2 人工态）。
 *
 * 状态全部来自 `stores/aiSupport`，本组件**不持有对话状态** —— 抽屉关着时通道仍在收事件，
 * 状态放组件里会在重挂时丢掉那段时间的消息。
 */
export default function AiSupportDrawer() {
  const navigate = useNavigate()
  const readContext = useChatContextReader()

  const isLoggedIn = useUserStore((s) => s.isLoggedIn)

  const open = useAiSupportStore((s) => s.open)
  const setOpen = useAiSupportStore((s) => s.setOpen)
  const messages = useAiSupportStore((s) => s.messages)
  const human = useAiSupportStore((s) => s.human)
  const statusText = useAiSupportStore((s) => s.statusText)
  const snapshotLoaded = useAiSupportStore((s) => s.snapshotLoaded)
  const snapshotFailed = useAiSupportStore((s) => s.snapshotFailed)
  const sending = useAiSupportStore((s) => s.sending)
  const sendingHuman = useAiSupportStore((s) => s.sendingHuman)
  const transferring = useAiSupportStore((s) => s.transferring)
  const send = useAiSupportStore((s) => s.send)
  const stop = useAiSupportStore((s) => s.stop)
  const transfer = useAiSupportStore((s) => s.transfer)
  const syncSnapshot = useAiSupportStore((s) => s.syncSnapshot)

  // 打开抽屉先回读快照：`GET /session` 是人工历史的**唯一来源**，SSE 只推连接期间的新事件。
  // 不做这一步，抽屉关着时发生的事（坐席回复、结束）就永远补不回来。
  useEffect(() => {
    if (open && isLoggedIn) void syncSnapshot()
  }, [open, isLoggedIn, syncSnapshot])

  // 人工态判据与 store 里一致：**`closed` 不是人工态**（终态后 /chat 回到 AI 流程）
  const inHuman = !!human && human.status !== 'closed'
  const mode: 'ai' | 'human' = inHuman ? 'human' : 'ai'

  // `transferring` 也必须算进去：转人工请求在飞的那一次往返内，store 里的 `human` **仍是 null**
  // （`transfer()` 要等响应回来才 set），而通道判定只看 `human` —— 此时放行，消息就会走去 AI 通道。
  // 与下面"快照未到不渲染输入框"是同一条理由：通道未知时，宁可让用户多等一会儿。
  const busy = sending || sendingHuman || transferring

  return (
    <Drawer
      open={open}
      onClose={() => setOpen(false)}
      width={420}
      rootClassName="ai-drawer"
      title={
        <span className="ai-drawer__title">
          <CustomerServiceOutlined /> 在线客服
        </span>
      }
      extra={
        isLoggedIn && !inHuman ? (
          <Button
            size="small"
            type="link"
            icon={<UserSwitchOutlined />}
            loading={transferring}
            onClick={() => void transfer('USER_REQUEST')}
          >
            {human ? '重新转人工' : '转人工'}
          </Button>
        ) : null
      }
    >
      <div className="ai-panel">
        {inHuman || human ? (
          <div className={`ai-panel__status ai-panel__status--${human?.status ?? 'ai'}`}>
            <Tag color={human?.status === 'human_active' ? 'green' : human?.status === 'closed' ? 'default' : 'orange'}>
              {human?.statusName ?? (inHuman ? '处理中' : '已结束')}
            </Tag>
            {/* 提示语一律是**服务端原话**（request 的 tip / human_close 的 text），前端不自编 */}
            {statusText ? <span className="ai-panel__tip">{statusText}</span> : null}
          </div>
        ) : null}

        {!isLoggedIn ? (
          <div className="ai-panel__guest">
            <p>登录后即可咨询商品、订单与售后问题</p>
            <Button type="primary" onClick={() => navigate('/login')}>
              去登录
            </Button>
          </div>
        ) : !snapshotLoaded ? (
          // 快照没到之前不渲染输入框：此时不知道买家在 AI 态还是人工态，
          // 让他在错误的通道上发第一条消息，比多等一会儿糟得多
          <div className="ai-panel__loading">
            {snapshotFailed ? (
              <>
                <span>会话信息加载失败</span>
                <Button size="small" onClick={() => void syncSnapshot()}>
                  重试
                </Button>
              </>
            ) : (
              <Spin />
            )}
          </div>
        ) : (
          <>
            <AiMessageList
              messages={messages}
              onTransfer={() => void transfer('AI_SUGGEST')}
              onQuickAsk={(text) => void send(text, readContext())}
            />
            {transferring ? (
              // 文案刻意只说**这一次请求**，不说**会话状态**：C4 前端 REQ §7 规则 3 禁止前端自编
              // "正在为您接入人工客服…"那类状态话术 —— 那是服务端 `tip` 的权威。
              // 这里对"有没有人接"不作任何声称，只是把"输入框为什么是灰的"讲明白。
              <div className="ai-panel__transferring">正在提交转人工请求…</div>
            ) : null}
            <AiComposer
              mode={mode}
              busy={busy}
              streaming={sending}
              onSend={(text) => void send(text, readContext())}
              onStop={stop}
            />
          </>
        )}
      </div>
    </Drawer>
  )
}
