import request from '@/api/request'
import type { LoginResult } from '@/types/api'

export interface RegisterPayload {
  username: string
  password: string
  email?: string
  phone?: string
}

export interface LoginPayload {
  username: string
  password: string
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return request.post('/auth/login', payload)
}

export function register(payload: RegisterPayload) {
  return request.post('/auth/register', payload)
}

export function logout() {
  return request.post('/auth/logout')
}
