import { useCallback, useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, Table } from 'antd'
import {
  AccountBookOutlined,
  PayCircleOutlined,
  ShoppingOutlined,
  UnorderedListOutlined,
  UserOutlined
} from '@ant-design/icons'
import { getAdminStats } from '@/api/admin/stats'
import OrderStatusBadge from '@/components/business/OrderStatusBadge'
import PageError from '@/components/business/PageError'
import type { AdminStatsVO } from '@/types/admin'
import './DashboardPage.scss'

export default function DashboardPage() {
  const [stats, setStats] = useState<AdminStatsVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAdminStats()
      .then(setStats)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const columns = [
    { title: '订单号', dataIndex: 'orderNo', width: 200 },
    { title: '买家', dataIndex: 'buyerName', width: 120, render: (v: string | null) => v || '-' },
    {
      title: '实付金额',
      dataIndex: 'actualAmount',
      width: 120,
      render: (v: number) => `¥${Number(v).toFixed(2)}`
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: number, row: AdminStatsVO['recentOrders'][number]) => (
        <OrderStatusBadge status={v} statusName={row.statusName} />
      )
    },
    { title: '下单时间', dataIndex: 'createdAt', width: 170 }
  ]

  if (error) {
    return <PageError onRetry={load} />
  }

  return (
    <div className="dashboard-page">
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={8} xl={4}>
          <Card loading={loading} className="stat-card">
            <Statistic title="用户总数" value={stats?.userCount ?? 0} prefix={<UserOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8} xl={4}>
          <Card loading={loading} className="stat-card">
            <Statistic title="商品总数" value={stats?.productCount ?? 0} prefix={<ShoppingOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8} xl={4}>
          <Card loading={loading} className="stat-card">
            <Statistic title="订单总数" value={stats?.orderCount ?? 0} prefix={<UnorderedListOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8} xl={4}>
          <Card loading={loading} className="stat-card">
            <Statistic title="待发货" value={stats?.pendingShipCount ?? 0} prefix={<PayCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={24} lg={8} xl={8}>
          <Card loading={loading} className="stat-card">
            <Statistic
              title="累计销售额"
              value={stats?.totalSalesAmount ?? 0}
              precision={2}
              prefix={<AccountBookOutlined />}
              suffix="元"
            />
          </Card>
        </Col>
      </Row>

      <Card title="最近订单" loading={loading} style={{ marginTop: 16 }}>
        <Table
          rowKey="orderNo"
          columns={columns}
          dataSource={stats?.recentOrders ?? []}
          pagination={false}
          size="middle"
        />
      </Card>
    </div>
  )
}
