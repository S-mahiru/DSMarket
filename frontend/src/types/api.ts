export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

export interface PageResult<T = any> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  avatar: string
  email: string
  phone: string
  gender: number
  role: string
  createdAt: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserInfo
}

export interface CategoryNode {
  id: number
  name: string
  level: number
  /**
   * 该分类下的公开可见商品数（REQ-20260913 §4.9），含整棵子树的商品。
   *
   * **可选**：前端可能先于后端上线，那时响应里没有这个键（E6）——
   * 组件必须退化成「只显示名字」，而不是显示 0。
   */
  productCount?: number
  children?: CategoryNode[]
}
