import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Card, Result, Spin } from 'antd'
import { getPaymentStatus } from '@/api/payment'
import PageError from '@/components/business/PageError'
import './PaymentResultPage.scss'

export default function PaymentResultPage() {
  const { orderNo } = useParams()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [success, setSuccess] = useState(false)
  const [amount, setAmount] = useState<number | null>(null)
  const [paymentNo, setPaymentNo] = useState<string | null>(null)
  const [payTime, setPayTime] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getPaymentStatus(orderNo!)
      .then((res) => {
        setSuccess(res.status === 1)
        setAmount(res.amount)
        setPaymentNo(res.paymentNo)
        setPayTime(res.payTime)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [orderNo])

  useEffect(() => {
    load()
  }, [load])

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
    <div className="payment-result-page">
      <Card className="payment-result-card">
        {success ? (
          <Result
            status="success"
            title="支付成功"
            subTitle={
              <div className="payment-result-detail">
                {amount != null && <p>支付金额：<b>¥{amount.toFixed(2)}</b></p>}
                {paymentNo && <p>交易流水号：{paymentNo}</p>}
                {payTime && <p>支付时间：{payTime}</p>}
              </div>
            }
            extra={[
              <Button type="primary" key="order" onClick={() => navigate(`/order/${orderNo}`)}>
                查看订单
              </Button>,
              <Button key="home" onClick={() => navigate('/')}>
                返回首页
              </Button>
            ]}
          />
        ) : (
          <Result
            status="warning"
            title="支付未完成"
            subTitle="未查询到成功的支付记录，请返回订单重新处理"
            extra={[
              <Button type="primary" key="order" onClick={() => navigate(`/order/${orderNo}`)}>
                查看订单
              </Button>,
              <Button key="retry" onClick={() => navigate(`/payment/${orderNo}`)}>
                重新支付
              </Button>
            ]}
          />
        )}
      </Card>
    </div>
  )
}
