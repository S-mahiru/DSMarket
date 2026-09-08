import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { AdminOrderListVO } from '@/types/admin'

export function getAdminOrders(params: {
  status?: number
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResult<AdminOrderListVO>> {
  return request.get('/admin/orders', { params })
}

export function shipOrder(orderNo: string) {
  return request.post(`/admin/orders/${orderNo}/ship`)
}
