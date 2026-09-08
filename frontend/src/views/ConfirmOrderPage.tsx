import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Empty, Input, Radio, Spin, message } from 'antd'
import { getCart } from '@/api/cart'
import { getAddresses } from '@/api/address'
import { createOrder } from '@/api/order'
import { useCartStore } from '@/stores/cart'
import type { Address } from '@/types/address'
import type { CartItem } from '@/types/cart'
import PageError from '@/components/business/PageError'
import './ConfirmOrderPage.scss'

const FREE_SHIPPING_THRESHOLD = 99
const SHIPPING_FEE = 10

export default function ConfirmOrderPage() {
  const navigate = useNavigate()
  const fetchCount = useCartStore((s) => s.fetchCount)
  const [addresses, setAddresses] = useState<Address[]>([])
  const [items, setItems] = useState<CartItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [addressId, setAddressId] = useState<number | null>(null)
  const [remark, setRemark] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(false)
    try {
      const [addrList, cartList] = await Promise.all([getAddresses(), getCart()])
      setAddresses(addrList)
      setItems(cartList.filter((i) => i.checked === 1))
      const defaultAddr = addrList.find((a) => a.isDefault === 1)
      if (defaultAddr) {
        setAddressId(defaultAddr.id)
      } else if (addrList.length > 0) {
        setAddressId(addrList[0].id)
      }
    } catch {
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const subtotal = useMemo(() => items.reduce((sum, i) => sum + i.subtotal, 0), [items])
  const shipping = subtotal >= FREE_SHIPPING_THRESHOLD || subtotal === 0 ? 0 : SHIPPING_FEE
  const actual = subtotal + shipping

  async function handleSubmit() {
    if (!addressId) {
      message.warning('请选择收货地址')
      return
    }
    setSubmitting(true)
    try {
      const result = await createOrder({ addressId, remark })
      message.success('订单提交成功')
      fetchCount()
      navigate(`/payment/${result.orderNo}`)
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
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

  if (items.length === 0) {
    return (
      <div className="checkout-empty">
        <Empty description="没有待结算的商品">
          <Button type="primary" onClick={() => navigate('/cart')}>
            去购物车勾选
          </Button>
        </Empty>
      </div>
    )
  }

  return (
    <div className="checkout-page">
      <h2 className="checkout-title">确认订单</h2>

      <Card title="收货地址" className="checkout-card">
        {addresses.length === 0 ? (
          <Empty description="还没有收货地址">
            <Button onClick={() => navigate('/addresses')}>去添加地址</Button>
          </Empty>
        ) : (
          <Radio.Group
            value={addressId}
            onChange={(e) => setAddressId(e.target.value)}
            className="checkout-address-group"
          >
            {addresses.map((addr) => (
              <Radio value={addr.id} key={addr.id} className="checkout-address-item">
                <span className="checkout-address-name">
                  {addr.receiverName} <span className="checkout-address-phone">{addr.receiverPhone}</span>
                  {addr.isDefault === 1 && <span className="checkout-address-default">默认</span>}
                </span>
                <span className="checkout-address-detail">
                  {addr.province} {addr.city} {addr.district} {addr.detailAddress}
                </span>
              </Radio>
            ))}
          </Radio.Group>
        )}
        <Button type="link" size="small" onClick={() => navigate('/addresses')} className="checkout-address-manage">
          管理地址
        </Button>
      </Card>

      <Card title="商品清单" className="checkout-card">
        {items.map((item) => (
          <div className="checkout-item" key={item.id}>
            <img src={item.productImage} alt={item.productName} className="checkout-item-img" />
            <div className="checkout-item-info">
              <div className="checkout-item-name">{item.productName}</div>
              {item.skuSpecs.length > 0 && (
                <div className="checkout-item-specs">
                  {item.skuSpecs.map((s) => `${s.key}: ${s.value}`).join(' ')}
                </div>
              )}
            </div>
            <div className="checkout-item-price">¥{item.unitPrice.toFixed(2)}</div>
            <div className="checkout-item-qty">x{item.quantity}</div>
            <div className="checkout-item-subtotal">¥{item.subtotal.toFixed(2)}</div>
          </div>
        ))}
      </Card>

      <Card className="checkout-card">
        <div className="checkout-remark">
          <span>订单备注</span>
          <Input.TextArea
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
            placeholder="选填，给卖家留言"
            rows={2}
            maxLength={200}
            className="checkout-remark-input"
          />
        </div>
        <div className="checkout-summary">
          <div className="checkout-summary-row">
            <span>商品总额</span>
            <span>¥{subtotal.toFixed(2)}</span>
          </div>
          <div className="checkout-summary-row">
            <span>运费</span>
            <span>{shipping === 0 ? '免运费' : `¥${shipping.toFixed(2)}`}</span>
          </div>
          <div className="checkout-summary-row checkout-summary-total">
            <span>应付总额</span>
            <span className="checkout-amount">¥{actual.toFixed(2)}</span>
          </div>
        </div>
      </Card>

      <div className="checkout-submit">
        <Button type="primary" size="large" loading={submitting} onClick={handleSubmit}>
          提交订单
        </Button>
      </div>
    </div>
  )
}
