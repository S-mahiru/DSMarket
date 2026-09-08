import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Result, Tag, message } from 'antd'
import { applyShop, getMyShop } from '@/api/shop'
import PageError from '@/components/business/PageError'
import type { ApplyShopPayload, ShopVO } from '@/types/shop'

export default function MerchantApplyPage() {
  const navigate = useNavigate()
  const [shop, setShop] = useState<ShopVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [submitting, setSubmitting] = useState(false)

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

  async function handleSubmit(values: ApplyShopPayload) {
    setSubmitting(true)
    try {
      await applyShop(values)
      message.success(shop ? '已重新提交入驻申请' : '入驻申请提交成功，等待管理员审核')
      setShop(await getMyShop())
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return null
  }
  if (error) {
    return <PageError onRetry={load} />
  }

  // 已提交 / 已开通：展示状态（商家中心由 /merchant 提供）
  if (shop && shop.status !== 2) {
    return (
      <div style={{ maxWidth: 560, margin: '48px auto' }}>
        <Result
          status={shop.status === 0 ? 'info' : 'success'}
          title={shop.status === 0 ? '入驻申请审核中' : '已开通店铺'}
          subTitle={
            shop.status === 0 ? '管理员审核通过后，你将获得商家身份，可在个人中心进入商家中心。' : undefined
          }
          extra={
            shop.status === 1 ? (
              <Button type="primary" onClick={() => navigate('/merchant')}>
                进入商家中心
              </Button>
            ) : undefined
          }
        >
          <Card size="small">
            <p>
              店铺名称：<b>{shop.shopName}</b>
            </p>
            <p>
              审核状态：
              <Tag color={shop.status === 0 ? 'orange' : 'green'}>{shop.statusName}</Tag>
            </p>
            <p>申请时间：{shop.createdAt}</p>
          </Card>
        </Result>
      </div>
    )
  }

  return (
    <div style={{ maxWidth: 560, margin: '32px auto' }}>
      <Card title="商家入驻申请">
        {shop && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={`上次申请被驳回${shop.auditRemark ? `：${shop.auditRemark}` : ''}，可修改后重新提交。`}
          />
        )}
        <Form layout="vertical" onFinish={handleSubmit} initialValues={shop ?? undefined}>
          <Form.Item
            name="shopName"
            label="店铺名称"
            rules={[
              { required: true, message: '请输入店铺名称' },
              { max: 100, message: '店铺名称不能超过100字符' }
            ]}
          >
            <Input placeholder="例如：黑海数码专营店" />
          </Form.Item>
          <Form.Item name="logo" label="店铺Logo（图片URL，可选）" rules={[{ max: 500, message: 'Logo地址过长' }]}>
            <Input placeholder="https://example.com/logo.png" />
          </Form.Item>
          <Form.Item
            name="description"
            label="店铺介绍"
            rules={[{ max: 500, message: '店铺介绍不能超过500字符' }]}
          >
            <Input.TextArea rows={3} placeholder="介绍一下你的主营商品与服务" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            {shop ? '重新提交申请' : '提交申请'}
          </Button>
        </Form>
      </Card>
    </div>
  )
}
