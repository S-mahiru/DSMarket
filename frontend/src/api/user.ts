import request from '@/api/request'
import type { UserInfo } from '@/types/api'

export function getProfile(): Promise<UserInfo> {
  return request.get('/user/profile')
}

export function updateProfile(data: { nickname?: string; avatar?: string; gender?: number }): Promise<UserInfo> {
  return request.put('/user/profile', data)
}

export function updatePassword(data: { oldPassword: string; newPassword: string }) {
  return request.put('/user/password', data)
}
