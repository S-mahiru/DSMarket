/** 管理端数据概览 */
export interface AdminStatsVO {
  userCount: number
  productCount: number
  orderCount: number
  pendingShipCount: number
  totalSalesAmount: number
  recentOrders: AdminOrderListVO[]
}

/** 管理端订单列表项 */
export interface AdminOrderListVO {
  orderNo: string
  buyerName: string | null
  totalAmount: number
  shippingFee: number
  actualAmount: number
  status: number
  statusName: string
  itemCount: number
  paymentTime: string | null
  deliveryTime: string | null
  createdAt: string
}

/** 管理端用户列表项 */
export interface UserAdminVO {
  id: number
  username: string
  nickname: string | null
  email: string | null
  phone: string | null
  role: string
  status: number
  lastLoginTime: string | null
  createdAt: string
}
