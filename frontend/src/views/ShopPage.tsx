import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Col, Empty, Pagination, Row, Select, Spin } from 'antd'
import { ShopOutlined } from '@ant-design/icons'
import { getSelfOperated } from '@/api/product'
import { getPublicShop, getPublicShopProducts } from '@/api/shop'
import type { ProductListVO } from '@/types/product'
import type { ShopPublicVO } from '@/types/shop'
import ProductCard from '@/components/business/ProductCard'
import PageError from '@/components/business/PageError'
import { DEFAULT_SORT, SORT_OPTIONS, parseSort } from '@/components/business/productSort'
import './ShopPage.scss'

const PAGE_SIZE = 20

/** 从 axios 错误里取 HTTP 状态码；拿不到就返回 undefined */
function statusOf(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

/**
 * 店铺售卖页（REQ-20260913 §4.6 / §4.7）。
 *
 * <p><b>单一路由</b> `/shop/:id`，`id === 'self'` 时走自营专区分支（§12.2 A8）——
 * 不拆成 `/shop/self` + `/shop/:id` 两条路由，免得纠结优先级。</p>
 */
export default function ShopPage() {
  const { id } = useParams()
  const isSelf = id === 'self'

  const [shop, setShop] = useState<ShopPublicVO | null>(null)
  const [products, setProducts] = useState<ProductListVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [sort, setSort] = useState(DEFAULT_SORT)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState(false)

  // 路由参数变化**不会**重挂载组件，若不重置就会带着上一家店的页码切店，
  // 落到空网格。这是 React 官方的「渲染期间调整 state」写法：它在提交前就重渲染，
  // 所以下面的 effect 只会用新的 page 跑一次，不会多打一次请求。
  const [loadedId, setLoadedId] = useState(id)
  if (loadedId !== id) {
    setLoadedId(id)
    setPage(1)
    setSort(DEFAULT_SORT)
    setShop(null)
  }

  const load = useCallback(() => {
    if (!id) return
    setLoading(true)
    setError(false)
    setNotFound(false)

    const query = { ...parseSort(sort), page, size: PAGE_SIZE }
    // 自营专区没有店铺实体可查，跳过详情请求（它的头部是静态文案）
    const detail = isSelf ? Promise.resolve(null) : getPublicShop(id).then(setShop)

    detail
      .then(() => (isSelf ? getSelfOperated(query) : getPublicShopProducts(id, query)))
      .then((res) => {
        setProducts(res.records)
        setTotal(res.total)
      })
      .catch((err: unknown) => {
        // E1：不存在 / 未开通 / 已软删都是 404，前端不区分（§8.3）
        setNotFound(statusOf(err) === 404)
        setError(true)
      })
      .finally(() => setLoading(false))
  }, [id, isSelf, sort, page])

  useEffect(() => {
    load()
  }, [load])

  if (error) {
    return notFound ? (
      // E1：不带重试按钮 —— 再点一次还是 404
      <PageError description="店铺不存在或已关闭" />
    ) : (
      <PageError onRetry={load} />
    )
  }

  if (loading) {
    return (
      <div className="page-loading">
        <Spin />
      </div>
    )
  }

  const shopName = isSelf ? '平台自营' : (shop?.shopName ?? '店铺')
  const description = isSelf ? null : shop?.description
  const logo = isSelf ? null : shop?.logo

  return (
    <div className="shop-page">
      <div className="shop-header">
        {logo ? (
          <img className="shop-logo" src={logo} alt={shopName} />
        ) : (
          <div className="shop-logo shop-logo-fallback">
            <ShopOutlined />
          </div>
        )}
        <div className="shop-info">
          <h1 className="shop-name">{shopName}</h1>
          {description && <p className="shop-desc">{description}</p>}
          {/* 「在售 N 件」用查询返回的真实 total */}
          <span className="shop-count">在售 {total} 件</span>
        </div>
      </div>

      <div className="shop-toolbar">
        <Select
          value={sort}
          style={{ width: 140 }}
          onChange={(v) => {
            setSort(v)
            setPage(1)
          }}
          options={SORT_OPTIONS}
        />
      </div>

      {products.length === 0 ? (
        <Empty description="该店铺暂无在售商品" style={{ padding: '60px 0' }} />
      ) : (
        <>
          <Row gutter={[16, 16]}>
            {products.map((p) => (
              <Col key={p.id} xs={12} sm={8} md={6}>
                <ProductCard product={p} />
              </Col>
            ))}
          </Row>
          <div className="shop-pagination">
            <Pagination
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              onChange={setPage}
            />
          </div>
        </>
      )}
    </div>
  )
}
