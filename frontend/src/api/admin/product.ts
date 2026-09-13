import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { ProductDetail, ProductFormData, ProductListVO } from '@/types/product'

// 表单类型已上移到 types/product.ts（商家端共用同一份，避免两处各写一份而漂移）。
// 这里保留 re-export，既有 `from '@/api/admin/product'` 的引用不受影响。
export type { ProductFormData, ProductSkuForm } from '@/types/product'

export function getAdminProducts(params: {
  page?: number
  size?: number
  keyword?: string
  categoryId?: number
}): Promise<PageResult<ProductListVO>> {
  return request.get('/admin/products', { params })
}

export function getAdminProduct(id: number | string): Promise<ProductDetail> {
  return request.get(`/admin/products/${id}`)
}

export function createProduct(data: ProductFormData): Promise<number> {
  return request.post('/admin/products', data)
}

export function updateProduct(id: number | string, data: ProductFormData) {
  return request.put(`/admin/products/${id}`, data)
}

export function updateProductStatus(id: number | string, status: number) {
  return request.put(`/admin/products/${id}/status`, null, { params: { status } })
}

export function deleteProduct(id: number | string) {
  return request.delete(`/admin/products/${id}`)
}
