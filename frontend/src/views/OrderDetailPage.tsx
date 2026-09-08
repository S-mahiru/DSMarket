import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Card, Empty, Popconfirm, Spin, Steps, message } from 'antd'
import { getOrderDetail, cancelOrder, receiveOrder } from '@/api/order'
import type { OrderDetailVO } from '@/types/order'
import OrderStatusBadge from '@/components/business/OrderStatusBadge'
import PageError from '@/components/business/PageError'
import './OrderDetailPage.scss'

export default function OrderDetailPage() {
  const { orderNo } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<OrderDetailVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [acting, setActing] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getOrderDetail(orderNo!)
      .then(setDetail)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [orderNo])

  useEffect(() => {
    load()
  }, [load])

  function handleCancel() {
    setActing(true)
    cancelOrder(orderNo!)
      .then(() => {
        message.success('订单已取消')
        load()
      })
      .catch(() => {})
      .finally(() => setActing(false))
  }

  function handleReceive() {
    setActing(true)
    receiveOrder(orderNo!)
      .then(() => {
        message.success('已确认收货')
        load()
      })
      .catch(() => {})
      .finally(() => setActing(false))
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
    return <Empty description="订单不存在" style={{ padding: '80px 0' }} />
  }

  return (
    <div className="order-detail-page">
      <h2 className="order-detail-title">
        订单详情 <OrderStatusBadge status={detail.status} statusName={detail.statusName} />
      </h2>

      <Card className="order-detail-card">
        <Steps
          current={detail.timeline.length - 1}
          items={detail.timeline.map((t) => ({ title: t.name, description: t.time }))}
          direction="vertical"
          size="small"
        />
      </Card>

      <Card title="收货信息" className="order-detail-card">
        <div className="order-receiver">
          <span className="order-receiver-name">
            {detail.address?.receiverName || '-'}
            <span className="order-receiver-phone">{detail.address?.receiverPhone}</span>
          </span>
          <span className="order-receiver-addr">
            {detail.address
              ? `${detail.address.province} ${detail.address.city} ${detail.address.district} ${detail.address.detailAddress}`
              : '-'}
          </span>
        </div>
      </Card>

      <Card title="商品信息" className="order-detail-card">
        <div className="order-item-head">
          <span>商品</span>
          <span>单价</span>
          <span>数量</span>
          <span>小计</span>
        </div>
        {detail.orderItems.map((item, idx) => (
          <div className="order-item-row" key={`${item.productId}-${item.skuId}-${idx}`}>
            <div className="order-item-info">
              <img src={item.productImage} alt={item.productName} className="order-item-img" />
              <div>
                <div className="order-item-name">{item.productName}</div>
                {item.skuSpecs.length > 0 && (
                  <div className="order-item-specs">{item.skuSpecs.map((s) => `${s.key}: ${s.value}`).join(' ')}</div>
                )}
              </div>
            </div>
            <span>¥{item.unitPrice.toFixed(2)}</span>
            <span>×{item.quantity}</span>
            <span className="order-item-subtotal">¥{item.subtotal.toFixed(2)}</span>
          </div>
        ))}
      </Card>

      <Card className="order-detail-card">
        <div className="order-detail-meta">
          <div>
            <span>订单编号：{detail.orderNo}</span>
            <span>下单时间：{detail.createdAt}</span>
            {detail.paymentMethod && <span>支付方式：{detail.paymentMethod}</span>}
            {detail.remark && <span>订单备注：{detail.remark}</span>}
          </div>
          <div className="order-amount-box">
            <div>
              <span>商品总额</span>
              <span>¥{detail.totalAmount.toFixed(2)}</span>
            </div>
            <div>
              <span>运费</span>
              <span>{detail.shippingFee === 0 ? '免运费' : `¥${detail.shippingFee.toFixed(2)}`}</span>
            </div>
            {detail.discountAmount > 0 && (
              <div>
                <span>优惠</span>
                <span>-¥{detail.discountAmount.toFixed(2)}</span>
              </div>
            )}
            <div className="order-amount-total">
              <span>实付金额</span>
              <b>¥{detail.actualAmount.toFixed(2)}</b>
            </div>
          </div>
        </div>
        <div className="order-detail-actions">
          <Button onClick={() => navigate('/orders')}>返回列表</Button>
          {detail.status === 0 && (
            <>
              <Button type="primary" onClick={() => navigate(`/payment/${detail.orderNo}`)}>
                去支付
              </Button>
              <Popconfirm title="确认取消该订单？" onConfirm={handleCancel} disabled={acting}>
                <Button danger>取消订单</Button>
              </Popconfirm>
            </>
          )}
          {detail.status === 2 && (
            <Popconfirm title="确认已收到商品？" onConfirm={handleReceive} disabled={acting}>
              <Button type="primary">确认收货</Button>
            </Popconfirm>
          )}
        </div>
      </Card>
    </div>
  )
}
