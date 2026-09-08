import request from '@/api/request'
import type { AdminStatsVO } from '@/types/admin'

export function getAdminStats(): Promise<AdminStatsVO> {
  return request.get('/admin/stats')
}
