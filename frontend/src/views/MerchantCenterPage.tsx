import { useCallback, useEffect, useState } from 'react'
import { Alert, Card, Descriptions, Tag } from 'antd'
import { getMyShop } from '@/api/shop'
import PageError from '@/components/business/PageError'
import type { ShopVO } from '@/types/shop'

export default function MerchantCenterPage() {
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
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="商家商品管理与订单处理将在下一迭代开放，敬请期待。"
      />
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
            <Tag color="green">{shop.statusName}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="开通时间">{shop.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  )
}
