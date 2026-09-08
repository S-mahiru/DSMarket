import { create } from 'zustand'
import { setToken, removeToken, setUser, getUser, getToken } from '@/api/request'
import { login as apiLogin, register as apiRegister, logout as apiLogout } from '@/api/auth'
import { getProfile as apiGetProfile } from '@/api/user'
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

export const useUserStore = create<UserState>((set) => ({
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
    set({ token: null, userInfo: null, isLoggedIn: false, isAdmin: false, isMerchant: false, nickname: '' })
  },

  async fetchProfile() {
    const res: UserInfo = await apiGetProfile()
    setUser(res)
    applyUser(set, res)
  }
}))
