import request from '@/api/request'
import type { ApplyShopPayload, ShopVO } from '@/types/shop'

export function applyShop(payload: ApplyShopPayload): Promise<ShopVO> {
  return request.post('/shop/apply', payload)
}

/** 我的店铺（未入驻返回 null） */
export function getMyShop(): Promise<ShopVO | null> {
  return request.get('/shop/mine')
}
