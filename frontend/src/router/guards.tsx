import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useUserStore } from '@/stores/user'

interface RequireAuthProps {
  role?: string
  children: ReactNode
}

/** 需要登录（可选指定角色），未满足则重定向 */
export function RequireAuth({ role, children }: RequireAuthProps) {
  const location = useLocation()
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const userRole = useUserStore((s) => s.userInfo?.role)

  // 需要登录但未登录 → 重定向到登录页
  if (!isLoggedIn) {
    return <Navigate to="/login" replace state={{ redirect: location.pathname + location.search }} />
  }

  // 需要特定角色但用户不是该角色（如 ADMIN / MERCHANT）→ 重定向到首页
  if (role && userRole !== role) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}

/** 已登录用户访问登录/注册页 → 重定向到首页 */
export function GuestGuard({ children }: { children: ReactNode }) {
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  return isLoggedIn ? <Navigate to="/" replace /> : <>{children}</>
}
