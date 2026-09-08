import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { message } from 'antd'
import router from '@/router'

const TOKEN_KEY = 'dsm_token'
const USER_KEY = 'dsm_user'

// 开发/生产均走同源 /api/v1，由 Vite dev proxy 或 Nginx 反向代理到后端
const request: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const { code, message: msg, data } = response.data
    if (code === 200) {
      return data
    }
    message.error(msg || '请求失败')
    return Promise.reject(new Error(msg))
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      switch (status) {
        case 401:
          localStorage.removeItem(TOKEN_KEY)
          localStorage.removeItem(USER_KEY)
          router.navigate('/login')
          break
        case 403:
          message.error('无权限访问')
          break
        case 429:
          message.warning('请求太频繁，请稍后再试')
          break
        default:
          message.error(data?.message || '服务器错误')
      }
    } else {
      message.error('网络异常，请检查连接')
    }
    return Promise.reject(error)
  }
)

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function setUser(user: any) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getUser(): any {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : null
}

export default request
