import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { ProductDetail, ProductListVO, SpecItem } from '@/types/product'

export interface ProductSkuForm {
  skuCode: string
  price: number
  originalPrice?: number
  stock: number
  specs: SpecItem[]
}

export interface ProductFormData {
  name: string
  title?: string
  brief?: string
  description?: string
  categoryId: number
  brand?: string
  unit?: string
  price: number
  originalPrice?: number
  stock: number
  mainImage?: string
  isFeatured: number
  status: number
  skus: ProductSkuForm[]
}

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
