import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Empty, Input, Popconfirm, Space, Table, Tag, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { getMerchantProducts, updateMerchantProductStatus } from '@/api/merchant/product'
import PageError from '@/components/business/PageError'
import type { ProductListVO } from '@/types/product'

/**
 * 商家商品管理（REQ-20260912 §4.6）。
 *
 * 结构对齐 `admin/ProductManagePage`，两处**不同**是刻意的：
 * ① 没有删除按钮 —— D1 本期不开放商家删除权，后端也没有这个端点；
 * ② 列表由后端按登录态自动限定自家店铺，前端**不传也不该传** shopId（§4.4 硬规则 1）。
 */
export default function MerchantProductManagePage() {
  const navigate = useNavigate()
  const [data, setData] = useState<ProductListVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getMerchantProducts({ page, size: 20, keyword: keyword || undefined })
      .then((res) => {
        setData(res.records)
        setTotal(res.total)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [page, keyword])

  useEffect(() => {
    load()
  }, [load])

  function handleToggleStatus(row: ProductListVO) {
    updateMerchantProductStatus(row.id, row.status === 1 ? 0 : 1)
      .then(() => {
        message.success('状态已更新')
        load()
      })
      .catch(() => {})
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '商品',
      dataIndex: 'name',
      render: (_: unknown, row: ProductListVO) => (
        <Space>
          <img
            src={row.mainImage || '/product-placeholder.svg'}
            alt=""
            style={{ width: 40, height: 40, objectFit: 'cover', borderRadius: 4 }}
          />
          {row.name}
        </Space>
      )
    },
    { title: '价格', dataIndex: 'price', width: 110, render: (v: number) => `¥${v.toFixed(2)}` },
    { title: '库存', dataIndex: 'stock', width: 80 },
    // 销量只读：它由订单成交累加，商家改不了，也不该改
    { title: '销量', dataIndex: 'sales', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: number) => (v === 1 ? <Tag color="green">上架</Tag> : <Tag>下架</Tag>)
    },
    {
      title: '操作',
      width: 140,
      render: (_: unknown, row: ProductListVO) => (
        <Space>
          <Button size="small" type="link" onClick={() => navigate(`/merchant/products/${row.id}/edit`)}>
            编辑
          </Button>
          <Popconfirm
            title={row.status === 1 ? '确认下架该商品？' : '确认上架该商品？'}
            onConfirm={() => handleToggleStatus(row)}
          >
            <Button size="small" type="link">
              {row.status === 1 ? '下架' : '上架'}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <div>
      <div style={{ display: 'flex', gap: 12, marginBottom: 16 }}>
        <Input.Search
          placeholder="搜索商品名称"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onSearch={(v) => {
            setPage(1)
            setKeyword(v)
          }}
          style={{ width: 280 }}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/merchant/products/create')}>
          新建商品
        </Button>
        <Button onClick={() => navigate('/merchant')}>返回商家中心</Button>
      </div>
      {error ? (
        <PageError onRetry={load} />
      ) : (
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data}
          locale={{
            // 空态给出口，而不是让商家对着一张空表发呆（§4.6 异常行）
            emptyText: (
              <Empty description="还没有商品">
                <Button type="primary" onClick={() => navigate('/merchant/products/create')}>
                  新建商品
                </Button>
              </Empty>
            )
          }}
          pagination={{
            current: page,
            pageSize: 20,
            total,
            showSizeChanger: false,
            onChange: (p) => setPage(p)
          }}
        />
      )}
    </div>
  )
}
