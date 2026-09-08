import request from '@/api/request'
import type { CartItem } from '@/types/cart'

export function getCart(): Promise<CartItem[]> {
  return request.get('/cart')
}

export function addToCart(data: { productId: number; skuId?: number | null; quantity: number }) {
  return request.post('/cart', data)
}

export function updateCartQuantity(id: number, quantity: number) {
  return request.put(`/cart/${id}`, { quantity })
}

export function updateCartChecked(id: number, checked: number) {
  return request.put(`/cart/${id}/check`, { checked })
}

export function checkAllCart(checked: number) {
  return request.put('/cart/check-all', { checked })
}

export function removeCartItem(id: number) {
  return request.delete(`/cart/${id}`)
}

export function clearCartChecked() {
  return request.delete('/cart/clear')
}

export function getCartCount(): Promise<{ count: number }> {
  return request.get('/cart/count')
}
