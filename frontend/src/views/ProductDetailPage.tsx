import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Empty, Image, InputNumber, Spin, Tag, message } from 'antd'
import { getProductDetail } from '@/api/product'
import { addToCart } from '@/api/cart'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import type { ProductDetail, ProductSku } from '@/types/product'
import PageError from '@/components/business/PageError'
import './ProductDetailPage.scss'

export default function ProductDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const fetchCount = useCartStore((s) => s.fetchCount)
  const [detail, setDetail] = useState<ProductDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [quantity, setQuantity] = useState(1)
  const [adding, setAdding] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getProductDetail(id!)
      .then((d) => {
        setDetail(d)
        setSelected({})
        setQuantity(1)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  // 当前选中的 SKU（多规格时按已选值匹配）
  const currentSku = useMemo<ProductSku | undefined>(() => {
    if (!detail) return undefined
    if (detail.skus.length === 0) return undefined
    if (detail.skus.length === 1) return detail.skus[0]
    return detail.skus.find((sku) => sku.specs.every((s) => selected[s.key] === s.value))
  }, [detail, selected])

  const displayPrice = currentSku?.price ?? detail?.price
  const displayStock = currentSku?.stock ?? detail?.stock
  const stockOut = displayStock == null || displayStock <= 0

  async function validateAndAddToCart() {
    if (!isLoggedIn) {
      message.warning('请先登录')
      navigate('/login')
      return false
    }
    if (!detail) return false
    // 多规格商品需选完整规格才能匹配 SKU
    if (detail.hasSku && detail.specDims.length > 0 && !currentSku) {
      message.warning('请选择完整规格')
      return false
    }
    await addToCart({ productId: detail.id, skuId: currentSku?.id ?? null, quantity })
    return true
  }

  async function handleAddToCart() {
    setAdding(true)
    try {
      // 仅在真正加入购物车后才提示成功（规格未选全/未登录等场景不误导用户）
      const ok = await validateAndAddToCart()
      if (ok) {
        message.success('已加入购物车')
        fetchCount()
      }
    } catch {
      // 拦截器已提示
    } finally {
      setAdding(false)
    }
  }

  async function handleBuyNow() {
    setAdding(true)
    try {
      const ok = await validateAndAddToCart()
      if (ok) {
        fetchCount()
        navigate('/checkout')
      }
    } catch {
      // 拦截器已提示
    } finally {
      setAdding(false)
    }
  }

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
  if (!detail) {
    return <Empty description="商品不存在" style={{ padding: '80px 0' }} />
  }

  return (
    <div className="product-detail">
      <div className="detail-gallery">
        <Image src={detail.mainImage} alt={detail.name} className="gallery-main" />
        {detail.subImages.length > 0 && (
          <div className="gallery-thumbs">
            {detail.subImages.map((img, i) => (
              <Image key={i} src={img} width={64} height={64} className="thumb" preview={false} />
            ))}
          </div>
        )}
      </div>

      <div className="detail-info">
        <h1 className="detail-name">{detail.name}</h1>
        <div className="detail-subtitle">{detail.title}</div>
        <div className="detail-price-box">
          <span className="detail-price">¥{(displayPrice ?? 0).toFixed(2)}</span>
          {detail.originalPrice != null && detail.originalPrice > (displayPrice ?? 0) && (
            <span className="detail-original">¥{detail.originalPrice.toFixed(2)}</span>
          )}
        </div>
        <div className="detail-meta">
          <span>销量 {detail.sales}</span>
          <span>库存 {stockOut ? '无货' : displayStock}</span>
          <span>{detail.brand}</span>
        </div>

        {detail.specDims.map((dim) => (
          <div key={dim.key} className="spec-row">
            <span className="spec-label">{dim.key}</span>
            <div className="spec-values">
              {dim.values.map((v) => (
                <Tag
                  key={v}
                  color={selected[dim.key] === v ? 'blue' : 'default'}
                  className="spec-value"
                  onClick={() => setSelected((prev) => ({ ...prev, [dim.key]: v }))}
                >
                  {v}
                </Tag>
              ))}
            </div>
          </div>
        ))}

        <div className="spec-row">
          <span className="spec-label">数量</span>
          <InputNumber
            min={1}
            max={Math.max(displayStock ?? 1, 1)}
            value={quantity}
            onChange={(v) => setQuantity(v || 1)}
          />
        </div>

        <div className="detail-actions">
          <Button type="primary" size="large" disabled={stockOut} loading={adding} onClick={handleAddToCart}>
            加入购物车
          </Button>
          <Button size="large" disabled={stockOut} loading={adding} onClick={handleBuyNow}>
            立即购买
          </Button>
        </div>

        {detail.description && (
          <div className="detail-desc" dangerouslySetInnerHTML={{ __html: detail.description }} />
        )}
      </div>
    </div>
  )
}
