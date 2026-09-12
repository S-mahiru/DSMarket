import { LikeOutlined, DislikeOutlined } from '@ant-design/icons'

interface Props {
  value?: 'up' | 'down'
  onFeedback: (satisfied: boolean) => void
}

/**
 * 点赞/点踩（C3-F2）。
 *
 * 反馈的**真值在服务端**：`replyId` 由该轮 `done` 下发，前端只是把它绑定到这两个按钮上。
 * 接口对"回查不到这个 replyId"也是 200 静默忽略（防遍历探测），所以这里不做任何
 * 成功/失败回执 —— 按钮置灰只是表明"这次点击已提交"。
 */
export default function AiFeedbackButtons({ value, onFeedback }: Props) {
  return (
    <div className="ai-feedback">
      <button
        type="button"
        className={value === 'up' ? 'is-active' : ''}
        disabled={!!value}
        aria-label="有帮助"
        onClick={() => onFeedback(true)}
      >
        <LikeOutlined />
      </button>
      <button
        type="button"
        className={value === 'down' ? 'is-active' : ''}
        disabled={!!value}
        aria-label="没帮助"
        onClick={() => onFeedback(false)}
      >
        <DislikeOutlined />
      </button>
    </div>
  )
}
