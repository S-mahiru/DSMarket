import { useEffect } from 'react'
import { Badge } from 'antd'
import { CustomerServiceOutlined } from '@ant-design/icons'
import { buyerStream } from '@/api/aiStreams'
import { useAiSupportStore } from '@/stores/aiSupport'
import { useUserStore } from '@/stores/user'
import AiSupportDrawer from './AiSupportDrawer'
import './AiSupportWidget.scss'

/**
 * 买家侧客服入口：右下悬浮气泡 + 抽屉。
 *
 * **挂载点**：`MainLayout` 的 `</main>` 之后、`.main-layout` 内（见 REQ-20260910 §3）。
 * `MainLayout` 只服务买家（登录/注册走 `BlankLayout`、后台走 `AdminLayout`），
 * 所以这个位置天然等于"买家面"，不需要维护一份路由白名单。
 *
 * **常驻连接也在这里挂**，不在抽屉里：抽屉关着的时候坐席仍可能回复或结束会话，
 * 挂在抽屉上就会丢掉那段时间的事件（C4 §4.7 的通道是常驻语义、且**不重放历史**）。
 */
export default function AiSupportWidget() {
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const open = useAiSupportStore((s) => s.open)
  const unread = useAiSupportStore((s) => s.unread)
  const setOpen = useAiSupportStore((s) => s.setOpen)
  const reset = useAiSupportStore((s) => s.reset)

  useEffect(() => {
    if (!isLoggedIn) {
      // 未登录时：清空上一个账号的对话（登出与 token 过期都走这里），**并且绝不开流** ——
      // 那条流会 401，而流层对 401 的处理是清 token + 跳登录页，等于一进首页就被弹去登录。
      reset()
      return
    }

    const offEvent = buyerStream.subscribe((evt) => useAiSupportStore.getState().handleStreamEvent(evt))
    // 每次（重）连成功都重拉快照：SSE 不重放历史，断连期间的坐席回复/结束只能靠它补齐。
    // 服务端每 30 分钟必关一次连接（SseEmitter 是绝对寿命不是空闲超时），所以这条路径是**常态**。
    const offConnected = buyerStream.onConnected(() => {
      void useAiSupportStore.getState().syncSnapshot()
    })
    const release = buyerStream.retain()
    void useAiSupportStore.getState().syncSnapshot()

    return () => {
      offEvent()
      offConnected()
      release()
    }
  }, [isLoggedIn, reset])

  return (
    <>
      <button
        type="button"
        className={`ai-widget-bubble${open ? ' is-open' : ''}`}
        aria-label="在线客服"
        onClick={() => setOpen(!open)}
      >
        <Badge count={unread} size="small" offset={[-2, 2]}>
          <CustomerServiceOutlined />
        </Badge>
      </button>
      <AiSupportDrawer />
    </>
  )
}
