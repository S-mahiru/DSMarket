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
