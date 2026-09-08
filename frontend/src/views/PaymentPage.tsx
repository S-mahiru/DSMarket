import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Card, Empty, Radio, Spin, message } from 'antd'
import { getOrderDetail } from '@/api/order'
import { payOrder } from '@/api/payment'
import type { OrderDetailVO } from '@/types/order'
import PageError from '@/components/business/PageError'
import './PaymentPage.scss'

const ORDER_TIMEOUT_MINUTES = 30

export default function PaymentPage() {
  const { orderNo } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<OrderDetailVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [method, setMethod] = useState('MOCK')
  const [paying, setPaying] = useState(false)
  const [remain, setRemain] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

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

  // 支付倒计时：下单时间 + 30 分钟
  useEffect(() => {
    if (!detail) return
    if (detail.status !== 0) return
    const deadline = new Date(detail.createdAt.replace(' ', 'T')).getTime() + ORDER_TIMEOUT_MINUTES * 60 * 1000
    const tick = () => {
      const remainMs = deadline - Date.now()
      setRemain(Math.max(0, Math.floor(remainMs / 1000)))
      if (remainMs <= 0 && timerRef.current) {
        clearInterval(timerRef.current)
      }
    }
    tick()
    timerRef.current = setInterval(tick, 1000)
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [detail])

  useEffect(() => {
    if (remain === 0 && detail && detail.status === 0) {
      message.warning('订单已超时')
    }
  }, [remain, detail])

  const remainText = useMemo(() => {
    if (!detail) return '--:--'
    if (detail.status !== 0) return '无需支付'
    if (remain <= 0) return '已超时'
    const m = String(Math.floor(remain / 60)).padStart(2, '0')
    const s = String(remain % 60).padStart(2, '0')
    return `${m}:${s}`
  }, [remain, detail])

  async function handlePay() {
    setPaying(true)
    try {
      await payOrder({ orderNo: orderNo!, paymentMethod: method })
      message.success('支付成功')
      navigate(`/payment/result/${orderNo}`)
    } catch {
      // 拦截器已提示
    } finally {
      setPaying(false)
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
    return <Empty description="订单不存在" style={{ padding: '80px 0' }} />
  }

  if (detail.status !== 0) {
    return (
      <div className="payment-page">
        <Card className="payment-card">
          <Empty description={`当前订单状态：${detail.statusName}`} style={{ padding: '40px 0' }}>
            <Button type="primary" onClick={() => navigate(`/order/${detail.orderNo}`)}>
              查看订单
            </Button>
          </Empty>
        </Card>
      </div>
    )
  }

  return (
    <div className="payment-page">
      <Card className="payment-card">
        <h2 className="payment-title">订单支付</h2>
        <div className="payment-amount">
          <span className="payment-amount-label">应付金额</span>
          <span className="payment-amount-value">¥{detail.actualAmount.toFixed(2)}</span>
        </div>
        <div className="payment-countdown">
          剩余支付时间 <b>{remainText}</b>
        </div>

        <div className="payment-methods">
          <div className="payment-methods-title">支付方式</div>
          <Radio.Group value={method} onChange={(e) => setMethod(e.target.value)} className="payment-methods-group">
            <Radio value="MOCK" className="payment-method-item">
              <span className="payment-method-label">模拟支付</span>
              <span className="payment-method-desc">（演示环境，点击即支付成功）</span>
            </Radio>
            <Radio value="ALIPAY" className="payment-method-item">
              <span className="payment-method-label">支付宝</span>
            </Radio>
            <Radio value="WECHAT" className="payment-method-item">
              <span className="payment-method-label">微信支付</span>
            </Radio>
          </Radio.Group>
        </div>

        <div className="payment-actions">
          <Button type="primary" size="large" loading={paying} disabled={remain <= 0} onClick={handlePay}>
            确认支付
          </Button>
        </div>
      </Card>
    </div>
  )
}
