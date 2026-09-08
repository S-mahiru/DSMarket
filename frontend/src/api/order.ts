import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { OrderCreateResult, OrderDetailVO, OrderListVO } from '@/types/order'

export function createOrder(data: { addressId: number; remark?: string }): Promise<OrderCreateResult> {
  return request.post('/orders', data)
}

export function getOrders(params: { status?: number; page?: number; size?: number }): Promise<PageResult<OrderListVO>> {
  return request.get('/orders', { params })
}

export function getOrderDetail(orderNo: string): Promise<OrderDetailVO> {
  return request.get(`/orders/${orderNo}`)
}

export function cancelOrder(orderNo: string, cancelReason?: string) {
  return request.post(`/orders/${orderNo}/cancel`, { cancelReason })
}

export function receiveOrder(orderNo: string) {
  return request.post(`/orders/${orderNo}/receive`)
}
