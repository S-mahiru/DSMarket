import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Col, Empty, Input, Pagination, Row, Select, Spin } from 'antd'
import { getCategories, getProducts } from '@/api/product'
import type { CategoryNode } from '@/types/api'
import type { ProductListVO } from '@/types/product'
import ProductCard from '@/components/business/ProductCard'
import CategoryTree from '@/components/business/CategoryTree'
import PageError from '@/components/business/PageError'
import './ProductListPage.scss'

export default function ProductListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const categoryId = searchParams.get('categoryId') ? Number(searchParams.get('categoryId')) : undefined
  const keyword = searchParams.get('keyword') || ''
  const sortBy = searchParams.get('sortBy') || 'sales'
  const sortOrder = searchParams.get('sortOrder') || 'desc'
  const page = Number(searchParams.get('page') || 1)
  const size = 20

  const [categories, setCategories] = useState<CategoryNode[]>([])
  const [products, setProducts] = useState<ProductListVO[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [searchInput, setSearchInput] = useState(keyword)

  useEffect(() => {
    getCategories().then(setCategories).catch(() => {})
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getProducts({ categoryId, keyword, sortBy, sortOrder, page, size })
      .then((res) => {
        setProducts(res.records)
        setTotal(res.total)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [categoryId, keyword, sortBy, sortOrder, page])

  useEffect(() => {
    load()
  }, [load])

  function updateParams(patch: Record<string, string | number | null | undefined>) {
    const next = new URLSearchParams(searchParams)
    Object.entries(patch).forEach(([k, v]) => {
      if (v == null || v === '') {
        next.delete(k)
      } else {
        next.set(k, String(v))
      }
    })
    if (!next.get('page')) {
      next.delete('page')
    }
    setSearchParams(next)
  }

  function handleSearch() {
    updateParams({ keyword: searchInput || null, page: 1 })
  }

  function handleSortChange(value: string) {
    const [by, order] = value.split('-')
    updateParams({ sortBy: by, sortOrder: order, page: 1 })
  }

  return (
    <div className="product-list-page">
      <aside className="list-sidebar">
        <h4 className="sidebar-title">商品分类</h4>
        <CategoryTree categories={categories} selectedId={categoryId} />
      </aside>

      <main className="list-main">
        <div className="list-toolbar">
          <Input.Search
            placeholder="搜索商品"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onSearch={handleSearch}
            allowClear
            style={{ width: 280 }}
          />
          <Select
            defaultValue="sales-desc"
            style={{ width: 140 }}
            onChange={handleSortChange}
            options={[
              { value: 'sales-desc', label: '销量优先' },
              { value: 'price-asc', label: '价格从低到高' },
              { value: 'price-desc', label: '价格从高到低' },
              { value: 'createdAt-desc', label: '最新上架' }
            ]}
          />
          <span className="list-total">共 {total} 件商品</span>
        </div>

        {error ? (
          <PageError onRetry={load} />
        ) : loading ? (
          <div className="page-loading">
            <Spin />
          </div>
        ) : products.length === 0 ? (
          <Empty description="没有找到相关商品" style={{ padding: '60px 0' }} />
        ) : (
          <>
            <Row gutter={[16, 16]}>
              {products.map((p) => (
                <Col key={p.id} xs={12} sm={8} md={6}>
                  <ProductCard product={p} />
                </Col>
              ))}
            </Row>
            <div className="list-pagination">
              <Pagination
                current={page}
                pageSize={size}
                total={total}
                showSizeChanger={false}
                onChange={(p) => updateParams({ page: p })}
              />
            </div>
          </>
        )}
      </main>
    </div>
  )
}
