import { useState } from 'react'
import { Link } from 'react-router-dom'
import { PictureOutlined } from '@ant-design/icons'
import type { ProductListVO } from '@/types/product'
import './ProductCard.scss'

interface Props {
  product: ProductListVO
}

/**
 * 折扣百分比，由 `price`/`originalPrice` 派生（REQ-20260913 §12.2 A3）。
 * 无原价、原价不高于现价、或原价非正数 → `null`（不画角标，也不编造数字）。
 */
function discountPercent(price: number, originalPrice: number | null): number | null {
  if (originalPrice == null || originalPrice <= 0 || originalPrice <= price) {
    return null
  }
  return Math.round((1 - price / originalPrice) * 100)
}

/**
 * 商品卡片（REQ-20260913 §4.2）。
 *
 * <p>根节点是 `<Link>` 而不是带 `onClick` 的 `<div>`：白得键盘 Tab 可达、回车进详情、
 * 中键/Ctrl+点击开新标签。代价是必须删掉 `useNavigate` 与 antd `Card` 的 import，
 * 否则 `noUnusedLocals` 会报 TS6133。</p>
 */
export default function ProductCard({ product }: Props) {
  const [imgFailed, setImgFailed] = useState(false)

  const discount = discountPercent(product.price, product.originalPrice)
  const stockOut = product.stock <= 0
  const hasImage = Boolean(product.mainImage) && !imgFailed

  return (
    <Link to={`/product/${product.id}`} className="product-card">
      <div className="product-card-media">
        {hasImage ? (
          // E7：种子图片指向 /uploads/*.jpg，本地大概率不存在 —— 必须有 onError 兜底
          <img src={product.mainImage} alt={product.name} onError={() => setImgFailed(true)} />
        ) : (
          <div className="product-card-placeholder">
            <PictureOutlined />
            <span>暂无图片</span>
          </div>
        )}
        {discount != null && <span className="product-card-badge badge-discount">-{discount}%</span>}
        {stockOut && <span className="product-card-badge badge-soldout">已售罄</span>}
      </div>

      <div className="product-card-body">
        <div className="product-card-name">{product.name}</div>
        <div className="product-card-meta">
          <span>已售 {product.sales}</span>
          <span className="meta-dot">·</span>
          <span>
            库存 {product.stock}
            {product.unit ?? ''}
          </span>
        </div>
        <div className="product-card-price-row">
          <span className="product-card-price">¥{product.price.toFixed(2)}</span>
          {product.originalPrice != null && product.originalPrice > product.price && (
            <span className="product-card-original">¥{product.originalPrice.toFixed(2)}</span>
          )}
        </div>
      </div>
    </Link>
  )
}
