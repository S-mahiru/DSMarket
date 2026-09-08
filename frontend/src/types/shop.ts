/** 商家侧我的店铺 */
export interface ShopVO {
  id: number
  userId: number
  shopName: string
  logo: string | null
  description: string | null
  status: number
  statusName: string
  auditRemark: string | null
  createdAt: string
}

/** 管理端店铺列表项 */
export interface ShopAdminVO {
  id: number
  userId: number
  ownerUsername: string | null
  shopName: string
  logo: string | null
  description: string | null
  status: number
  statusName: string
  auditRemark: string | null
  createdAt: string
  updatedAt: string
}

/** 入驻申请提交参数 */
export interface ApplyShopPayload {
  shopName: string
  logo?: string
  description?: string
}

/** 审核参数 */
export interface AuditShopPayload {
  status: 1 | 2
  auditRemark?: string
}
