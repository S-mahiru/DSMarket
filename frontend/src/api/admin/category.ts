import request from '@/api/request'

export interface AdminCategory {
  id: number
  name: string
  parentId: number
  level: number
  sortOrder: number
  status: number
}

export function getAdminCategories(): Promise<AdminCategory[]> {
  return request.get('/admin/categories')
}

export function createCategory(data: Partial<AdminCategory>): Promise<AdminCategory> {
  return request.post('/admin/categories', data)
}

export function updateCategory(id: number, data: Partial<AdminCategory>) {
  return request.put(`/admin/categories/${id}`, data)
}

export function deleteCategory(id: number) {
  return request.delete(`/admin/categories/${id}`)
}
