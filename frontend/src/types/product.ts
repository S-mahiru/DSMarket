export interface ProductListVO {
  id: number
  name: string
  brief: string
  price: number
  originalPrice: number | null
  mainImage: string
  stock: number
  sales: number
  unit: string
  status: number
}

export interface SpecItem {
  key: string
  value: string
}

export interface SpecDim {
  key: string
  values: string[]
}

export interface ProductSku {
  id: number
  skuCode: string
  price: number
  originalPrice: number | null
  stock: number
  specs: SpecItem[]
  image: string | null
  sortOrder: number
}

export interface ProductDetail {
  id: number
  name: string
  title: string
  brief: string
  description: string
  mainImage: string
  subImages: string[]
  detailImages: string[]
  categoryId: number
  categoryName: string
  brand: string
  unit: string
  price: number
  originalPrice: number | null
  stock: number
  sales: number
  hasSku: number
  isFeatured: number
  status: number
  specDims: SpecDim[]
  skus: ProductSku[]
  /**
   * 所属店铺 id，**自营商品为 `null`**（REQ-20260913 §4.5）。
   *
   * 两个字段都声明为**可选**，是因为全局 Jackson 配置 `default-property-inclusion=non_null`
   * 会把值为 null 的键**整个省略**（不是给 `null`）—— 自营商品的响应里根本不存在这两个键。
   * 写成 `shopId: number | null` 会让 TS 以为键一定在，运行时却 undefined。
   */
  shopId?: number | null
  shopName?: string | null
}

export interface ProductSkuForm {
  skuCode: string
  price: number
  originalPrice?: number
  stock: number
  specs: SpecItem[]
}

/**
 * 新建/编辑商品的表单载荷，admin 与商家端**共用同一份**（REQ-20260912 §4.6「表单字段沿用 ProductFormDTO」）。
 *
 * <p>**这里没有 `shopId`，是刻意的**：商品归属由服务端从登录态解析后赋值，
 * 客户端多传一个 `shopId` 连绑定都不会发生（`ProductFormDTO` 上没有这个字段）。
 * 谁哪天想往这里加 `shopId`，先读 §4.4 硬规则 1。</p>
 */
export interface ProductFormData {
  name: string
  title?: string
  brief?: string
  description?: string
  categoryId: number
  brand?: string
  unit?: string
  price: number
  originalPrice?: number
  stock: number
  mainImage?: string
  isFeatured: number
  status: number
  skus: ProductSkuForm[]
}

export interface ProductQuery {
  keyword?: string
  categoryId?: number
  brand?: string
  minPrice?: number
  maxPrice?: number
  sortBy?: string
  sortOrder?: string
  page?: number
  size?: number
}
