import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ProductCard from './ProductCard'
import type { ProductListVO } from '@/types/product'

// REQ-20260913 §14 T24。断言一律基于**渲染出来的 DOM**，不直接调 `discountPercent`
// （那个函数没导出）—— 要验的是「卡片上到底画没画角标」，不是「函数返回了什么」。

function makeProduct(over: Partial<ProductListVO> = {}): ProductListVO {
  return {
    id: 1,
    name: '测试商品',
    brief: '',
    price: 80,
    originalPrice: 100,
    mainImage: '/uploads/a.jpg',
    stock: 10,
    sales: 3,
    unit: '件',
    status: 1,
    ...over
  }
}

function renderCard(product: ProductListVO) {
  return render(
    <MemoryRouter>
      <ProductCard product={product} />
    </MemoryRouter>
  )
}

describe('ProductCard 折扣角标（由 price / originalPrice 派生）', () => {
  it('原价高于现价 → 画出角标且数值正确', () => {
    const { container } = renderCard(makeProduct({ price: 80, originalPrice: 100 }))
    expect(container.querySelector('.badge-discount')?.textContent).toBe('-20%')
  })

  it('四舍五入到整数百分比', () => {
    const { container } = renderCard(makeProduct({ price: 79.99, originalPrice: 100 }))
    expect(container.querySelector('.badge-discount')?.textContent).toBe('-20%')
  })

  it('originalPrice 为 null → 不画角标（不是画个空的）', () => {
    const { container } = renderCard(makeProduct({ originalPrice: null }))
    expect(container.querySelector('.badge-discount')).toBeNull()
  })

  it('originalPrice 键整个缺失 → 不画角标，且不得出现「-NaN%」', () => {
    // 这条才是线上真实路径：后端全局 `default-property-inclusion=non_null`，
    // `originalPrice` 为 null 时**键会被整个省略**，前端拿到的是 `undefined` 而不是 `null`
    // （同 REQ-20260913 §15.2 记的 shopId/shopName）。TS 类型写着 `number | null` 拦不住它。
    // 注意 `undefined <= 0` 是 **false**（NaN 比较），所以只靠 `<= 0` 那半条守卫挡不住，
    // 必须由 `originalPrice == null` 这半条挡住 —— 这条用例就是钉它的。
    const p: Partial<ProductListVO> = makeProduct()
    delete p.originalPrice
    const { container } = renderCard(p as ProductListVO)
    expect(container.querySelector('.badge-discount')).toBeNull()
    expect(container.textContent).not.toContain('NaN')
  })

  it('原价等于现价 → 不画（否则会出现「-0%」）', () => {
    const { container } = renderCard(makeProduct({ price: 100, originalPrice: 100 }))
    expect(container.querySelector('.badge-discount')).toBeNull()
  })

  it('原价低于现价（脏数据）→ 不画，且不得算出负折扣', () => {
    const { container } = renderCard(makeProduct({ price: 120, originalPrice: 100 }))
    expect(container.querySelector('.badge-discount')).toBeNull()
  })

  it('原价为 0 → 不画（除零保护）', () => {
    const { container } = renderCard(makeProduct({ price: 80, originalPrice: 0 }))
    expect(container.querySelector('.badge-discount')).toBeNull()
  })
})

describe('ProductCard 售罄角标', () => {
  it('stock = 0 → 画「已售罄」', () => {
    const { container } = renderCard(makeProduct({ stock: 0 }))
    expect(container.querySelector('.badge-soldout')?.textContent).toBe('已售罄')
  })

  it('stock > 0 → 不画', () => {
    const { container } = renderCard(makeProduct({ stock: 1 }))
    expect(container.querySelector('.badge-soldout')).toBeNull()
  })

  it('折扣与售罄可以同时出现（分列左右，不互斥）', () => {
    const { container } = renderCard(makeProduct({ price: 80, originalPrice: 100, stock: 0 }))
    expect(container.querySelector('.badge-discount')?.textContent).toBe('-20%')
    expect(container.querySelector('.badge-soldout')?.textContent).toBe('已售罄')
  })
})

describe('ProductCard 图片兜底（E7）', () => {
  it('有 mainImage → 渲染 img 且 src 指向它', () => {
    const { container } = renderCard(makeProduct({ mainImage: '/uploads/x.jpg' }))
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/uploads/x.jpg')
    expect(container.querySelector('.product-card-placeholder')).toBeNull()
  })

  it('mainImage 为空 → 渲染「暂无图片」占位、不渲染 img', () => {
    const { container } = renderCard(makeProduct({ mainImage: '' }))
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('.product-card-placeholder')?.textContent).toContain('暂无图片')
  })
})

describe('ProductCard 结构与元信息', () => {
  it('根节点是指向 /product/:id 的链接（白得键盘可达与中键开新标签）', () => {
    const { container } = renderCard(makeProduct({ id: 42 }))
    const root = container.firstElementChild as HTMLElement
    expect(root.tagName).toBe('A')
    expect(root.getAttribute('href')).toBe('/product/42')
  })

  it('元信息行是「已售 N · 库存 M+单位」', () => {
    const { container } = renderCard(makeProduct({ sales: 7, stock: 12, unit: '台' }))
    const meta = container.querySelector('.product-card-meta')?.textContent ?? ''
    expect(meta).toContain('已售 7')
    expect(meta).toContain('库存 12台')
  })

  it('不渲染 brief（REQ §12.2 A4：会撑破元信息行）', () => {
    const { container } = renderCard(makeProduct({ brief: '这段简介不该出现在卡片上' }))
    expect(container.textContent).not.toContain('这段简介不该出现在卡片上')
  })
})
