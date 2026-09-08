import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { AuditShopPayload, ShopAdminVO } from '@/types/shop'

export function getAdminShops(params: {
  status?: number
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResult<ShopAdminVO>> {
  return request.get('/admin/shops', { params })
}

export function auditShop(id: number, payload: AuditShopPayload) {
  return request.post(`/admin/shops/${id}/audit`, payload)
}
