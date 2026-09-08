import request from '@/api/request'
import type { PaymentVO } from '@/types/order'

export function payOrder(data: { orderNo: string; paymentMethod?: string }): Promise<PaymentVO> {
  return request.post('/payment/pay', data)
}

export function getPaymentStatus(orderNo: string): Promise<PaymentVO> {
  return request.get(`/payment/status/${orderNo}`)
}
