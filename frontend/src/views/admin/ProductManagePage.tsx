import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Input, Popconfirm, Space, Table, Tag, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { deleteProduct, getAdminProducts, updateProductStatus } from '@/api/admin/product'
import PageError from '@/components/business/PageError'
import type { ProductListVO } from '@/types/product'

export default function ProductManagePage() {
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
    getAdminProducts({ page, size: 20, keyword: keyword || undefined })
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
    updateProductStatus(row.id, row.status === 1 ? 0 : 1)
      .then(() => {
        message.success('状态已更新')
        load()
      })
      .catch(() => {})
  }

  function handleDelete(row: ProductListVO) {
    deleteProduct(row.id)
      .then(() => {
        message.success('已删除')
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
          <img src={row.mainImage || '/favicon.svg'} alt="" style={{ width: 40, height: 40, objectFit: 'cover', borderRadius: 4 }} />
          {row.name}
        </Space>
      )
    },
    { title: '价格', dataIndex: 'price', width: 110, render: (v: number) => `¥${v.toFixed(2)}` },
    { title: '库存', dataIndex: 'stock', width: 80 },
    { title: '销量', dataIndex: 'sales', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: number) => (v === 1 ? <Tag color="green">上架</Tag> : <Tag>下架</Tag>)
    },
    {
      title: '操作',
      width: 200,
      render: (_: unknown, row: ProductListVO) => (
        <Space>
          <Button size="small" type="link" onClick={() => navigate(`/admin/products/${row.id}/edit`)}>
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
          <Popconfirm title="确认删除该商品？" onConfirm={() => handleDelete(row)}>
            <Button size="small" type="link" danger>
              删除
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
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/admin/products/create')}>
          新增商品
        </Button>
      </div>
      {error ? (
        <PageError onRetry={load} />
      ) : (
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data}
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
