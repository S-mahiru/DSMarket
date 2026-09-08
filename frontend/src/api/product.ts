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

export function getProductDetail(id: number | string): Promise<ProductDetail> {
  return request.get(`/products/${id}`)
}
