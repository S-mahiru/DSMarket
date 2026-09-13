import { useEffect } from 'react'
import { Outlet, useMatches } from 'react-router-dom'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'

interface RouteHandle {
  title?: string
}

export default function App() {
  const matches = useMatches()
  const deepest = matches
    .filter((m) => (m.handle as RouteHandle | undefined)?.title)
    .at(-1)
  const title = (deepest?.handle as RouteHandle | undefined)?.title || 'DSMarket'

  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const fetchCount = useCartStore((s) => s.fetchCount)

  useEffect(() => {
    document.title = `${title} - 黑海商城`
  }, [title])

  // 会话恢复只在 `main.tsx` 里做一次（且必须在渲染前完成，理由见那里的注释）。
  // 这里曾经**又调了一次** `restoreSession()` —— 它同步读 localStorage 且幂等，所以看不出差别；
  // 但自 REQ-20260913-既有缺陷修复 B2 起它会附带一次 profile 请求，留着就会每次加载打两次。
  // 注：`main.tsx` 是 StrictMode，若恢复逻辑挂在本组件的 effect 上，dev 下还会再翻倍。

  useEffect(() => {
    if (isLoggedIn) {
      fetchCount()
    }
  }, [isLoggedIn, fetchCount])

  return <Outlet />
}
