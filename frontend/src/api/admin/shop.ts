import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { AuditShopPayload, CloseShopPayload, ShopAdminVO } from '@/types/shop'

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

/**
 * 关闭一个已开通的店铺（终局，不可重开）。
 *
 * 不复用 `auditShop(id, { status: 2 })`：那会把「驳回申请」与「关闭店铺」两个不同
 * 前置条件的动作塞进同一个校验分支，而且 `2` 在代码里是可重新申请的驳回态。
 */
export function closeShop(id: number, payload: CloseShopPayload) {
  return request.post(`/admin/shops/${id}/close`, payload)
}
