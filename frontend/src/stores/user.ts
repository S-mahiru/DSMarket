import { create } from 'zustand'
import { setToken, removeToken, setUser, getUser, getToken } from '@/api/request'
import { login as apiLogin, register as apiRegister, logout as apiLogout } from '@/api/auth'
import { getProfile as apiGetProfile } from '@/api/user'
import { resetAiStreams } from '@/api/aiStreams'
import type { LoginResult, UserInfo } from '@/types/api'

interface UserState {
  token: string | null
  userInfo: UserInfo | null
  isLoggedIn: boolean
  isAdmin: boolean
  isMerchant: boolean
  nickname: string
  restoreSession: () => void
  login: (credentials: { username: string; password: string }) => Promise<void>
  register: (data: { username: string; password: string; email?: string; phone?: string }) => Promise<void>
  logout: () => void
  fetchProfile: () => Promise<void>
}

function applyUser(set: (partial: Partial<UserState>) => void, user: UserInfo | null) {
  set({
    userInfo: user,
    isAdmin: user?.role === 'ADMIN',
    isMerchant: user?.role === 'MERCHANT',
    nickname: user?.nickname || user?.username || ''
  })
}

export const useUserStore = create<UserState>((set, get) => ({
  token: null,
  userInfo: null,
  isLoggedIn: false,
  isAdmin: false,
  isMerchant: false,
  nickname: '',

  restoreSession() {
    const token = getToken()
    const saved = getUser()
    set({ token, isLoggedIn: !!token })
    applyUser(set, saved)

    // REQ-20260913-既有缺陷修复 B2：`role` 是 JWT 签发时的快照，而 localStorage 里缓存的那份
    // 永远不变 —— 商家被审核通过后，前台仍按旧角色渲染，**必须重新登录**才生效（刷新也没用）。
    // 故同步恢复之后补拉一次 profile，以后端当前值为准。
    //
    // 必须保持本方法**同步返回**：`main.tsx` 在渲染前调它，靠它避免首帧守卫误判已登录用户。
    // 所以是 fire-and-forget，不能 await。
    if (token) {
      get()
        .fetchProfile()
        .catch((err: { response?: { status?: number } }) => {
          // 401：拦截器已清掉 localStorage 并跳登录页，但它够不到 store 状态
          // （反向 import 会成环），不在这里对齐的话路由守卫仍认为已登录。
          if (err?.response?.status === 401) {
            set({
              token: null,
              userInfo: null,
              isLoggedIn: false,
              isAdmin: false,
              isMerchant: false,
              nickname: ''
            })
          }
          // 其他失败（网络异常等）一律保持 localStorage 里的旧值，即降级到改动前的行为
        })
    }
  },

  async login(credentials) {
    const res: LoginResult = await apiLogin(credentials)
    setToken(res.token)
    setUser(res.user)
    set({ token: res.token, isLoggedIn: true })
    applyUser(set, res.user)
  },

  async register(data) {
    await apiRegister(data)
  },

  logout() {
    apiLogout().catch(() => {})
    removeToken()
    // 两条常驻 AI 通道必须在这里断：`logout()` 自己不导航（`MainLayout` 是裸调的），
    // 若挂在路由变化上，登出后连接会带着已失效的 token 继续活着并无限 401 重连。
    resetAiStreams()
    set({ token: null, userInfo: null, isLoggedIn: false, isAdmin: false, isMerchant: false, nickname: '' })
  },

  async fetchProfile() {
    const res: UserInfo = await apiGetProfile()
    setUser(res)
    applyUser(set, res)
  }
}))
