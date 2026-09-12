import { useState } from 'react'
import { Button, Input } from 'antd'
import { SendOutlined, StopOutlined } from '@ant-design/icons'
import './AiComposer.scss'

/**
 * `content` 长度**分四档**（C1 §2 权威，2026-09-10 由 D14/D15 落地）：
 * 绝对上限 4000（锚点命中也不豁免）/ **AI 态 500** / 锚点命中豁免 500 且转存截断 ≤2000 / PH 态 ≤2000。
 *
 * 这里守的是 **AI 态那一档（500）** —— 超过 ~400 就提示可以转人工，因为转人工后走的是
 * 人工通道（上限 2000），长申诉才有地方说。**不要把 4000 当成输入框上限**：
 * 后三档都写着 2000、甚至 4000，但它们分属锚点转存 / PH 缓冲 / 防 DoS 绝对上限，
 * 没有一个是"AI 态聊天框能打多少字"。
 */
const AI_CONTENT_MAX = 500
const AI_CONTENT_SOFT_LIMIT = 400
/** 人工通道上限（C4 §3.2）：人工客服存在的意义就是让买家把完整申诉说清楚 */
const HUMAN_CONTENT_MAX = 2000

interface Props {
  mode: 'ai' | 'human'
  /** 有请求在飞（AI 轮或人工消息），输入框禁用 */
  busy: boolean
  /** 正在流式生成 —— 此时按钮变"停止" */
  streaming: boolean
  onSend: (text: string) => void
  onStop: () => void
}

export default function AiComposer({ mode, busy, streaming, onSend, onStop }: Props) {
  const [text, setText] = useState('')

  const isAi = mode === 'ai'
  const max = isAi ? AI_CONTENT_MAX : HUMAN_CONTENT_MAX
  const nearLimit = isAi && text.length > AI_CONTENT_SOFT_LIMIT
  const canSend = text.trim().length > 0 && !busy

  function submit() {
    if (!canSend) return
    onSend(text)
    setText('')
  }

  return (
    <div className="ai-composer">
      {nearLimit ? (
        <div className="ai-composer__hint">
          内容较长，建议点右上角「转人工」由客服处理（人工通道最多 {HUMAN_CONTENT_MAX} 字）
        </div>
      ) : null}

      <div className="ai-composer__row">
        <Input.TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={isAi ? '请输入您的问题…' : '请输入留言内容…'}
          autoSize={{ minRows: 1, maxRows: 4 }}
          maxLength={max}
          disabled={busy}
          onPressEnter={(e) => {
            // Shift+Enter 换行；裸 Enter 发送（与聊天工具一致的肌肉记忆）
            if (!e.shiftKey) {
              e.preventDefault()
              submit()
            }
          }}
        />

        {streaming ? (
          <Button danger icon={<StopOutlined />} onClick={onStop}>
            停止
          </Button>
        ) : (
          // 一轮在飞时禁用发送：服务端是**单飞行**（`ai:inflight:{userId}`），
          // 并发第二个 /chat 会 409 CONCURRENT_GENERATION，与其让用户撞一次错误不如直接拦下
          <Button type="primary" icon={<SendOutlined />} disabled={!canSend} onClick={submit}>
            发送
          </Button>
        )}
      </div>
    </div>
  )
}
