import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { ProductDetail, ProductFormData, ProductListVO } from '@/types/product'

/**
 * 商家商品接口（REQ-20260912 §4.5）。
 *
 * **没有 `shopId` 参数，也没有 delete。** 前者：归属由服务端从登录态解析，传了也没用
 * （§4.4 硬规则 1）；后者：D1 已拍板本期不开放商家删除权，只提供上下架。
 * 后端也确实没有 DELETE 端点 —— 这里不写，是为了不让前端出现一个必然失败的按钮。
 */

export function getMerchantProducts(params: {
  page?: number
  size?: number
  keyword?: string
  categoryId?: number
}): Promise<PageResult<ProductListVO>> {
  return request.get('/merchant/products', { params })
}

export function getMerchantProduct(id: number | string): Promise<ProductDetail> {
  return request.get(`/merchant/products/${id}`)
}

export function createMerchantProduct(data: ProductFormData): Promise<number> {
  return request.post('/merchant/products', data)
}

export function updateMerchantProduct(id: number | string, data: ProductFormData) {
  return request.put(`/merchant/products/${id}`, data)
}

export function updateMerchantProductStatus(id: number | string, status: number) {
  return request.put(`/merchant/products/${id}/status`, null, { params: { status } })
}
