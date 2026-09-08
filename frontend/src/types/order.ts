import type { SpecItem } from '@/types/product'

export interface OrderItem {
  productId: number
  skuId: number | null
  productName: string
  productImage: string
  skuSpecs: SpecItem[]
  unitPrice: number
  quantity: number
  subtotal: number
}

export interface OrderListVO {
  orderNo: string
  totalAmount: number
  shippingFee: number
  actualAmount: number
  status: number
  statusName: string
  itemCount: number
  orderItems: OrderItem[]
  createdAt: string
}

export interface AddressSnapshot {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
  zipCode: string
  label: string
}

export interface TimelineItem {
  status: number
  name: string
  time: string
}

export interface OrderDetailVO {
  orderNo: string
  totalAmount: number
  shippingFee: number
  discountAmount: number
  actualAmount: number
  status: number
  statusName: string
  remark: string
  paymentMethod: string | null
  address: AddressSnapshot
  orderItems: OrderItem[]
  timeline: TimelineItem[]
  createdAt: string
}

export interface OrderCreateResult {
  orderNo: string
  actualAmount: number
  status: number
}

export interface PaymentVO {
  paymentNo: string | null
  status: number
  payTime: string | null
  amount: number | null
}

export interface OrderStatusOption {
  value: number | ''
  label: string
}

export const ORDER_STATUS_OPTIONS: OrderStatusOption[] = [
  { value: '', label: '全部' },
  { value: 0, label: '待付款' },
  { value: 1, label: '已付款' },
  { value: 2, label: '已发货' },
  { value: 3, label: '已收货' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已取消' },
  { value: 6, label: '已关闭' }
]
