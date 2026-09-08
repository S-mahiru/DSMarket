import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Empty, Popconfirm, Spin, Tabs, message } from 'antd'
import { getOrders, cancelOrder, receiveOrder } from '@/api/order'
import type { OrderListVO } from '@/types/order'
import { ORDER_STATUS_OPTIONS } from '@/types/order'
import OrderStatusBadge from '@/components/business/OrderStatusBadge'
import PageError from '@/components/business/PageError'
import './OrderListPage.scss'

const PAGE_SIZE = 5

export default function OrderListPage() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<number | ''>('')
  const [orders, setOrders] = useState<OrderListVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [acting, setActing] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getOrders({ status: status === '' ? undefined : (status as number), page, size: PAGE_SIZE })
      .then((res) => {
        setOrders(res.records)
        setTotal(res.total)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [status, page])

  useEffect(() => {
    load()
  }, [load])

  function handleTabChange(key: string) {
    setStatus(key === 'all' ? '' : Number(key))
    setPage(1)
  }

  function handleCancel(orderNo: string) {
    setActing(true)
    cancelOrder(orderNo)
      .then(() => {
        message.success('订单已取消')
        load()
      })
      .catch(() => {})
      .finally(() => setActing(false))
  }

  function handleReceive(orderNo: string) {
    setActing(true)
    receiveOrder(orderNo)
      .then(() => {
        message.success('已确认收货')
        load()
      })
      .catch(() => {})
      .finally(() => setActing(false))
  }

  function goPay(orderNo: string) {
    navigate(`/payment/${orderNo}`)
  }

  return (
    <div className="order-page">
      <h2 className="order-title">我的订单</h2>

      <Tabs
        activeKey={status === '' ? 'all' : String(status)}
        onChange={handleTabChange}
        items={ORDER_STATUS_OPTIONS.map((o) => ({ key: o.value === '' ? 'all' : String(o.value), label: o.label }))}
      />

      {error ? (
        <PageError onRetry={load} />
      ) : loading ? (
        <div className="page-loading">
          <Spin />
        </div>
      ) : orders.length === 0 ? (
        <Empty description="暂无订单" style={{ padding: '60px 0' }} />
      ) : (
        <>
          <div className="order-list">
            {orders.map((order) => (
              <div
                className="order-card"
                key={order.orderNo}
                onClick={() => navigate(`/order/${order.orderNo}`)}
              >
                <div className="order-card-head">
                  <span className="order-no">订单号：{order.orderNo}</span>
                  <span className="order-time">{order.createdAt}</span>
                  <OrderStatusBadge status={order.status} statusName={order.statusName} />
                </div>
                <div className="order-card-body">
                  {order.orderItems.slice(0, 3).map((item) => (
                    <img key={`${order.orderNo}-${item.productId}-${item.skuId}`} src={item.productImage} alt={item.productName} className="order-thumb" />
                  ))}
                  <span className="order-count">共 {order.itemCount} 件</span>
                  <span className="order-total">
                    实付 <b>¥{order.actualAmount.toFixed(2)}</b>
                  </span>
                </div>
                <div className="order-card-actions" onClick={(e) => e.stopPropagation()}>
                  <Button size="small" onClick={() => navigate(`/order/${order.orderNo}`)}>
                    查看详情
                  </Button>
                  {order.status === 0 && (
                    <>
                      <Button size="small" type="primary" onClick={() => goPay(order.orderNo)}>
                        去支付
                      </Button>
                      <Popconfirm title="确认取消该订单？" onConfirm={() => handleCancel(order.orderNo)} disabled={acting}>
                        <Button size="small" danger>
                          取消订单
                        </Button>
                      </Popconfirm>
                    </>
                  )}
                  {order.status === 2 && (
                    <Popconfirm title="确认已收到商品？" onConfirm={() => handleReceive(order.orderNo)} disabled={acting}>
                      <Button size="small" type="primary">
                        确认收货
                      </Button>
                    </Popconfirm>
                  )}
                </div>
              </div>
            ))}
          </div>

          {total > PAGE_SIZE && (
            <div className="order-pagination">
              <Button disabled={page <= 1} onClick={() => setPage(page - 1)}>
                上一页
              </Button>
              <span>
                {page} / {Math.ceil(total / PAGE_SIZE)}
              </span>
              <Button disabled={page >= Math.ceil(total / PAGE_SIZE)} onClick={() => setPage(page + 1)}>
                下一页
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
