import { useCallback, useEffect, useState } from 'react'
import { Button, Input, Popconfirm, Select, Table, message } from 'antd'
import { getAdminOrders, shipOrder } from '@/api/admin/order'
import OrderStatusBadge from '@/components/business/OrderStatusBadge'
import PageError from '@/components/business/PageError'
import type { AdminOrderListVO } from '@/types/admin'
import { ORDER_STATUS_OPTIONS } from '@/types/order'
import './OrderManagePage.scss'

export default function OrderManagePage() {
  const [data, setData] = useState<AdminOrderListVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<number | undefined>(undefined)
  const [keyword, setKeyword] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [shipLoading, setShipLoading] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAdminOrders({ page, size: 10, status, keyword: keyword || undefined })
      .then((res) => {
        setData(res.records)
        setTotal(res.total)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [page, status, keyword])

  useEffect(() => {
    load()
  }, [load])

  function handleShip(orderNo: string) {
    setShipLoading(orderNo)
    shipOrder(orderNo)
      .then(() => {
        message.success('发货成功')
        load()
      })
      .catch(() => {})
      .finally(() => setShipLoading(null))
  }

  const columns = [
    { title: '订单号', dataIndex: 'orderNo', width: 200 },
    { title: '买家', dataIndex: 'buyerName', width: 120, render: (v: string | null) => v || '-' },
    { title: '商品件数', dataIndex: 'itemCount', width: 90 },
    {
      title: '实付金额',
      dataIndex: 'actualAmount',
      width: 110,
      render: (v: number) => `¥${Number(v).toFixed(2)}`
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: number, row: AdminOrderListVO) => (
        <OrderStatusBadge status={v} statusName={row.statusName} />
      )
    },
    { title: '支付时间', dataIndex: 'paymentTime', width: 170, render: (v: string | null) => v || '-' },
    { title: '下单时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作',
      width: 100,
      render: (_: unknown, row: AdminOrderListVO) =>
        row.status === 1 ? (
          <Popconfirm title="确认发货？" onConfirm={() => handleShip(row.orderNo)}>
            <Button size="small" type="primary" loading={shipLoading === row.orderNo}>
              发货
            </Button>
          </Popconfirm>
        ) : (
          '-'
        )
    }
  ]

  return (
    <div>
      <div className="order-manage-toolbar">
        <Select
          placeholder="订单状态"
          allowClear
          style={{ width: 160 }}
          value={status}
          onChange={(v) => {
            setPage(1)
            setStatus(v)
          }}
          options={ORDER_STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
        />
        <Input.Search
          placeholder="订单号 / 买家名"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onSearch={(v) => {
            setPage(1)
            setKeyword(v)
          }}
          style={{ width: 260 }}
        />
      </div>
      {error ? (
        <PageError onRetry={load} />
      ) : (
        <Table
          rowKey="orderNo"
          loading={loading}
          columns={columns}
          dataSource={data}
          pagination={{
            current: page,
            pageSize: 10,
            total,
            showSizeChanger: false,
            onChange: (p) => setPage(p)
          }}
        />
      )}
    </div>
  )
}
