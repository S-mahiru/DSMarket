import type { SpecItem } from '@/types/product'

export interface CartItem {
  id: number
  productId: number
  productName: string
  productImage: string
  skuId: number | null
  skuSpecs: SpecItem[]
  unitPrice: number
  quantity: number
  subtotal: number
  stock: number
  checked: number
}
