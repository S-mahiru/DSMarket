import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Space, Tag } from 'antd'
import { AppstoreOutlined } from '@ant-design/icons'
import { getMyShop } from '@/api/shop'
import PageError from '@/components/business/PageError'
import { SHOP_STATUS } from '@/types/shop'
import type { ShopVO } from '@/types/shop'

/** 状态 → Tag 颜色。文案一律用服务端的 `statusName`，这里只问颜色不问措辞。 */
const STATUS_COLOR: Record<number, string> = {
  [SHOP_STATUS.PENDING]: 'orange',
  [SHOP_STATUS.OPEN]: 'green',
  [SHOP_STATUS.REJECTED]: 'red',
  [SHOP_STATUS.CLOSED]: 'default'
}

export default function MerchantCenterPage() {
  const navigate = useNavigate()
  const [shop, setShop] = useState<ShopVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getMyShop()
      .then((data) => setShop(data))
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  if (loading) {
    return null
  }
  if (error) {
    return <PageError onRetry={load} />
  }
  if (!shop) {
    return <PageError description="暂无店铺信息，请先提交入驻申请" />
  }

  return (
    <div style={{ maxWidth: 720, margin: '24px auto' }}>
      {/* 关闭态必须说清楚：Q3 拍板「角色不降」，所以这个账号**仍然进得来**商家中心、
          商品管理**仍然可用**（改的是后台数据），但商品在前台已经全站不可见。
          不写这段提示，商家只会看到"店铺用不了"而不知道发生了什么、也不知道不可撤销。 */}
      {shop.status === SHOP_STATUS.CLOSED && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="店铺已被关闭"
          description={
            <>
              你的店铺已被平台关闭，店内商品在商城前台已全部不可见，且<b>不可恢复</b>，也不能重新提交入驻申请。
              {shop.auditRemark ? `关闭理由：${shop.auditRemark}` : ''}
              后台的商品数据仍然保留，你可以查看，但改动不会在前台生效。
            </>
          }
        />
      )}
      <Card title="经营管理" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Button type="primary" icon={<AppstoreOutlined />} onClick={() => navigate('/merchant/products')}>
            商品管理
          </Button>
          {/* 商家订单管理需要 dsm_order 加 shop_id，REQ §11 第 3 条已明确不在本期范围。
              这里如实标注「未开放」，而不是留一个点了没反应的按钮。 */}
          <Button disabled title="需订单表支持按店铺归属，本期未开放">
            订单管理（未开放）
          </Button>
        </Space>
      </Card>
      <Card title="我的店铺">
        <Descriptions column={1} bordered size="middle">
          <Descriptions.Item label="店铺名称">
            <b>{shop.shopName}</b>
          </Descriptions.Item>
          <Descriptions.Item label="店铺Logo">
            {shop.logo ? <img src={shop.logo} alt="logo" style={{ maxWidth: 120, maxHeight: 120 }} /> : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="店铺介绍">{shop.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={STATUS_COLOR[shop.status] ?? 'default'}>{shop.statusName}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="开通时间">{shop.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  )
}
