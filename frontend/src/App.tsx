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

  const restoreSession = useUserStore((s) => s.restoreSession)
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const fetchCount = useCartStore((s) => s.fetchCount)

  useEffect(() => {
    document.title = `${title} - 黑海商城`
  }, [title])

  useEffect(() => {
    restoreSession()
  }, [restoreSession])

  useEffect(() => {
    if (isLoggedIn) {
      fetchCount()
    }
  }, [isLoggedIn, fetchCount])

  return <Outlet />
}
