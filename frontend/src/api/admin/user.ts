import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { UserAdminVO } from '@/types/admin'

export function getAdminUsers(params: {
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResult<UserAdminVO>> {
  return request.get('/admin/users', { params })
}

export function updateUserStatus(id: number, status: number) {
  return request.put(`/admin/users/${id}/status`, null, { params: { status } })
}
