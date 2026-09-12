import { useEffect } from 'react'
import { Alert, Spin } from 'antd'
import { seatStream } from '@/api/aiStreams'
import { seatLogoutOnUnload } from '@/api/admin/aiSupport'
import { useAdminAiSupportStore } from '@/stores/adminAiSupport'
import AdminSessionColumn from '@/components/ai/AdminSessionColumn'
import AdminSessionDetail from '@/components/ai/AdminSessionDetail'
import PageError from '@/components/business/PageError'
import './AiSupportWorkbenchPage.scss'

/** 三列表（C4 §4.5）。顺序与服务端 `AdminSupportBoardVO` 一致，不按数量动态排 —— 列会跳。 */
const COLUMNS = [
  { key: 'queued', tone: 'queued', title: '排队中' },
  { key: 'active', tone: 'active', title: '进行中' },
  { key: 'left', tone: 'left', title: '已留言' }
] as const

/**
 * AI 客服工作台（C4 §4.5 三列表 + §4.2 在线心跳 + §4.8 admin 通道）。
 *
 * **"在线"是心跳维持出来的，不是这条 SSE 连接**（`AdminSupportController.stream()` 明写
 * "SSE 断线 ≠ 掉线"）。所以本页一挂载就开心跳、一离开就上报离线 ——
 * 后者尤其重要：坐席切到订单页后心跳停了，服务端在线键还能活 45s，
 * 那 45 秒里买家会被"有人在线"骗着干等接入。
 */
export default function AiSupportWorkbenchPage() {
  const board = useAdminAiSupportStore((s) => s.board)
  const boardLoaded = useAdminAiSupportStore((s) => s.boardLoaded)
  const boardLoading = useAdminAiSupportStore((s) => s.boardLoading)
  const selectedId = useAdminAiSupportStore((s) => s.selectedId)
  const seat = useAdminAiSupportStore((s) => s.seat)
  const refreshBoard = useAdminAiSupportStore((s) => s.refreshBoard)
  const selectSession = useAdminAiSupportStore((s) => s.selectSession)
  const startSeat = useAdminAiSupportStore((s) => s.startSeat)
  const stopSeat = useAdminAiSupportStore((s) => s.stopSeat)

  useEffect(() => {
    const offEvent = seatStream.subscribe((evt) =>
      useAdminAiSupportStore.getState().handleSeatEvent(evt)
    )
    // 每次（重）连都重拉快照：SSE 不重放历史。30 分钟绝对超时是常态，这条路径天天走。
    const offConnected = seatStream.onConnected(() =>
      useAdminAiSupportStore.getState().onStreamConnected()
    )
    const release = seatStream.retain()
    startSeat()
    void refreshBoard()

    // 关标签页/刷新时补一次离线上报。`sendBeacon` 在这里用不了 —— 它不能带 Authorization，
    // 而 JwtAuthenticationFilter 只认请求头，beacon 过去必然 401（等于没上报）。
    const onPageHide = () => seatLogoutOnUnload()
    window.addEventListener('pagehide', onPageHide)

    return () => {
      window.removeEventListener('pagehide', onPageHide)
      offEvent()
      offConnected()
      release()
      stopSeat()
    }
  }, [startSeat, stopSeat, refreshBoard])

  return (
    <div className="workbench">
      {seat === 'offline' ? (
        <Alert
          className="workbench__seat"
          type="warning"
          showIcon
          message="心跳中断，已按离线处理"
          description="超过 45 秒没有心跳成功，服务端在线键即将过期 —— 这段时间买家转人工不会被告知「有客服在线」。看板仍可用，但请先确认网络或后端是否正常。"
        />
      ) : null}

      {!boardLoaded ? (
        // **快照没成功就不渲染三列**：那会把"没拉到"显示成"没有会话"。
        // 与买家抽屉"快照没到不渲染输入框"是同一条纪律。
        <div className="workbench__state">
          {boardLoading ? (
            <Spin />
          ) : (
            <PageError description="会话列表加载失败" onRetry={() => void refreshBoard()} />
          )}
        </div>
      ) : (
        <div className="workbench__body">
          <div className="workbench__board">
            {COLUMNS.map((c) => (
              <AdminSessionColumn
                key={c.key}
                tone={c.tone}
                title={c.title}
                sessions={board[c.key]}
                selectedId={selectedId}
                onSelect={(id) => void selectSession(id)}
              />
            ))}
          </div>
          <div className="workbench__detail">
            <AdminSessionDetail />
          </div>
        </div>
      )}
    </div>
  )
}
