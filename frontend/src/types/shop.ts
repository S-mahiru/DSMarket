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

/**
 * 前台公开店铺（`GET /api/v1/shops/{id}`，REQ-20260913 §8.2）。
 *
 * **只有这 4 个字段**，别顺手往这里加 `status`/`userId` —— 后端 `ShopPublicVO` 就这 4 个，
 * 多出来的字段永远不会被返回（那正是 §8.2 要防的信息泄漏）。
 *
 * `logo`/`description` 声明为可选：全局 `non_null` 序列化会把空值键整个省略。
 */
export interface ShopPublicVO {
  id: number
  shopName: string
  logo?: string | null
  description?: string | null
}

/** 入驻申请提交参数 */
export interface ApplyShopPayload {
  shopName: string
  logo?: string
  description?: string
}

/**
 * 审核参数。
 *
 * **关闭不走这里**（REQ-20260913-店铺关闭能力 §4.1）：审核的前置条件是「待审核」，
 * 关闭的前置条件是「已开通」。用 `status: 2` 表达关闭会让关闭被一次重新申请撤销
 * （`apply` 对 2 放行）。关闭请用 `closeShop` + `CloseShopPayload`。
 */
export interface AuditShopPayload {
  status: 1 | 2
  auditRemark?: string
}

/**
 * 关闭店铺参数。整个 body 可省（后端 `@RequestBody(required=false)`），
 * 理由写入 `dsm_shop.audit_remark`；缺省会**清空**该列，所以前端强制填。
 */
export interface CloseShopPayload {
  auditRemark?: string
}

/**
 * 店铺状态取值（与后端 `ShopStatusEnum` 一一对应，**唯一事实来源在那边**）。
 *
 * `CLOSED` 是 2026-09-13 新增的：此前 `2` 被文档当成「已驳回/关闭」两用，
 * 但代码里 `2` 是「申请被驳回，可改资料重来」—— 拿它当关闭会让店铺被一次重新申请复活。
 */
export const SHOP_STATUS = {
  PENDING: 0,
  OPEN: 1,
  REJECTED: 2,
  CLOSED: 3
} as const
