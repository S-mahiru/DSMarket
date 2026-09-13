import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { ProductListVO, ProductQuery } from '@/types/product'
import type { ApplyShopPayload, ShopPublicVO, ShopVO } from '@/types/shop'

export function applyShop(payload: ApplyShopPayload): Promise<ShopVO> {
  return request.post('/shop/apply', payload)
}

/** 我的店铺（未入驻返回 null） */
export function getMyShop(): Promise<ShopVO | null> {
  return request.get('/shop/mine')
}

// ---- 以下走公开前缀 `/shops`（**复数**）。
// 单数 `/shop` 是商家侧的 apply/mine，靠 anyRequest().authenticated() 兜底 ——
// 把公开路径写成单数会让「提交入驻申请」变成匿名可调（REQ-20260913 §6.3）。

/** 公开店铺信息。不存在 / 未开通 / 已软删 → 404（三者不可区分） */
export function getPublicShop(id: number | string): Promise<ShopPublicVO> {
  return request.get(`/shops/${id}`)
}

/** 该店铺的在售商品。店铺不可见时同样 404，**不会**退化成空列表 */
export function getPublicShopProducts(
  id: number | string,
  query: ProductQuery
): Promise<PageResult<ProductListVO>> {
  return request.get(`/shops/${id}/products`, { params: query })
}
