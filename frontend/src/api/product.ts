import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { CategoryNode } from '@/types/api'
import type { ProductDetail, ProductListVO, ProductQuery } from '@/types/product'

export function getCategories(): Promise<CategoryNode[]> {
  return request.get('/categories')
}

export function getProducts(query: ProductQuery): Promise<PageResult<ProductListVO>> {
  return request.get('/products', { params: query })
}

export function getFeatured(limit = 8): Promise<ProductListVO[]> {
  return request.get('/products/featured', { params: { limit } })
}

/**
 * 自营专区商品（`shop_id IS NULL`，REQ-20260913 §4.7 / §12.2 A7）。
 *
 * 路径刻意放在**产品命名空间**下：若做成 `/shops/self` 会与 `/shops/{id}` 的 `Long`
 * 路径变量冲突 —— `self` 转 `Long` 失败会被兜底成 **500 而不是 404**。
 */
export function getSelfOperated(query: ProductQuery): Promise<PageResult<ProductListVO>> {
  return request.get('/products/self-operated', { params: query })
}

export function getProductDetail(id: number | string): Promise<ProductDetail> {
  return request.get(`/products/${id}`)
}
