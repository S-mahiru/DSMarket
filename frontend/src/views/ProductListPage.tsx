import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Col, Empty, Input, Pagination, Row, Select, Spin } from 'antd'
import { getCategories, getProducts } from '@/api/product'
import type { CategoryNode } from '@/types/api'
import type { ProductListVO } from '@/types/product'
import ProductCard from '@/components/business/ProductCard'
import CategoryChips from '@/components/business/CategoryChips'
import PageError from '@/components/business/PageError'
import { SORT_OPTIONS } from '@/components/business/productSort'
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

  /**
   * 切分类（REQ-20260913 §4.3）。
   *
   * <p>两个既有缺陷都在这一行上修掉：旧 `CategoryTree` 内部直接
   * `navigate('/products?categoryId=N')` ——（1）把 `keyword`/`sortBy` 一并冲掉；
   * （2）不带 `page`，在第 N 页切分类会落到空列表。走 `updateParams` 打补丁则
   * 其余参数原样保留，并显式把 `page` 归 1。</p>
   */
  function handleCategorySelect(id?: number) {
    updateParams({ categoryId: id ?? null, page: 1 })
  }

  function handleClearFilters() {
    setSearchInput('')
    updateParams({ categoryId: null, keyword: null, page: 1 })
  }

  const hasFilter = categoryId != null || keyword !== ''

  return (
    <div className="product-list-page">
      {/* 列表页不传 totalCount：这里的 `total` 是筛选后的条数，当「全部」用会显示错数 */}
      <CategoryChips categories={categories} selectedId={categoryId} onSelect={handleCategorySelect} />

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
          value={`${sortBy}-${sortOrder}`}
          style={{ width: 140 }}
          onChange={handleSortChange}
          options={SORT_OPTIONS}
        />
        <span className="list-total">共 {total} 件商品</span>
        {hasFilter && (
          <Button type="link" size="small" onClick={handleClearFilters}>
            清除筛选
          </Button>
        )}
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
    </div>
  )
}
