import { useNavigate } from 'react-router-dom'
import { Card } from 'antd'
import type { ProductListVO } from '@/types/product'
import './ProductCard.scss'

interface Props {
  product: ProductListVO
}

export default function ProductCard({ product }: Props) {
  const navigate = useNavigate()

  return (
    <Card
      hoverable
      cover={
        <img
          src={product.mainImage || '/favicon.svg'}
          alt={product.name}
          className="product-card-img"
          onClick={() => navigate(`/product/${product.id}`)}
        />
      }
      className="product-card"
      onClick={() => navigate(`/product/${product.id}`)}
    >
      <div className="product-card-name">{product.name}</div>
      <div className="product-card-brief">{product.brief}</div>
      <div className="product-card-meta">
        <span className="product-card-price">¥{product.price.toFixed(2)}</span>
        {product.originalPrice != null && product.originalPrice > product.price && (
          <span className="product-card-original">¥{product.originalPrice.toFixed(2)}</span>
        )}
        <span className="product-card-sales">已售 {product.sales}</span>
      </div>
    </Card>
  )
}
