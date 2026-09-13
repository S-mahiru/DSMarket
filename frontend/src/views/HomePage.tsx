import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Col, Empty, Row, Spin } from 'antd'
import { getCategories, getFeatured } from '@/api/product'
import type { CategoryNode } from '@/types/api'
import type { ProductListVO } from '@/types/product'
import ProductCard from '@/components/business/ProductCard'
import CategoryChips from '@/components/business/CategoryChips'
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

  // 「全部」= 根节点计数之和，等于不带筛选的列表 total（REQ §10 第 3 条）。
  // 任一环节 productCount 缺失就整体不传 —— E6 要求退化成「只显示名字」，
  // 而不是显示一个错的「全部 0」。
  const totalCount =
    categories.length > 0 && categories.every((c) => c.productCount != null)
      ? categories.reduce((sum, c) => sum + (c.productCount ?? 0), 0)
      : undefined

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
        <div className="section-head">
          <h3 className="section-title">商品分类</h3>
          <Link to="/products" className="section-more">
            查看全部 ›
          </Link>
        </div>
        <CategoryChips
          categories={categories}
          totalCount={totalCount}
          onSelect={(id) => navigate(id == null ? '/products' : `/products?categoryId=${id}`)}
        />
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
