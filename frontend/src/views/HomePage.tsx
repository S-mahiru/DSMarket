import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Col, Empty, Row, Spin, Tag } from 'antd'
import { getCategories, getFeatured } from '@/api/product'
import type { CategoryNode } from '@/types/api'
import type { ProductListVO } from '@/types/product'
import ProductCard from '@/components/business/ProductCard'
import PageError from '@/components/business/PageError'
import './HomePage.scss'

export default function HomePage() {
  const navigate = useNavigate()
  const [categories, setCategories] = useState<CategoryNode[]>([])
  const [featured, setFeatured] = useState<ProductListVO[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = () => {
    setLoading(true)
    setError(false)
    Promise.all([getCategories(), getFeatured()])
      .then(([cats, feats]) => {
        setCategories(cats)
        setFeatured(feats)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  if (error) {
    return <PageError onRetry={load} />
  }

  if (loading) {
    return (
      <div className="page-loading">
        <Spin />
      </div>
    )
  }

  return (
    <div className="home-page">
      <section className="home-section">
        <h3 className="section-title">商品分类</h3>
        <div className="category-nav">
          {categories.map((c) => (
            <Tag
              key={c.id}
              className="category-tag"
              onClick={() => navigate(`/products?categoryId=${c.id}`)}
            >
              {c.name}
            </Tag>
          ))}
        </div>
      </section>

      <section className="home-section">
        <h3 className="section-title">推荐商品</h3>
        {featured.length === 0 ? (
          <Empty description="暂无推荐商品" />
        ) : (
          <Row gutter={[16, 16]}>
            {featured.map((p) => (
              <Col key={p.id} xs={12} sm={8} md={6} lg={6}>
                <ProductCard product={p} />
              </Col>
            ))}
          </Row>
        )}
      </section>
    </div>
  )
}
