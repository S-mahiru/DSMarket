import { useCallback, useEffect, useState } from 'react'
import { Button, Input, Modal, Popconfirm, Select, Space, Table, Tag, message } from 'antd'
import { auditShop, getAdminShops } from '@/api/admin/shop'
import PageError from '@/components/business/PageError'
import type { ShopAdminVO } from '@/types/shop'

const STATUS_FILTERS = [
  { value: undefined, label: '全部' },
  { value: 0, label: '待审核' },
  { value: 1, label: '已开通' },
  { value: 2, label: '已驳回' }
]

function statusTag(status: number) {
  const map: Record<number, { color: string; label: string }> = {
    0: { color: 'orange', label: '待审核' },
    1: { color: 'green', label: '已开通' },
    2: { color: 'red', label: '已驳回' }
  }
  const item = map[status] ?? { color: 'default', label: '未知' }
  return <Tag color={item.color}>{item.label}</Tag>
}

export default function ShopManagePage() {
  const [data, setData] = useState<ShopAdminVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<number | undefined>(undefined)
  const [keyword, setKeyword] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [acting, setActing] = useState<number | null>(null)
  const [rejectTarget, setRejectTarget] = useState<ShopAdminVO | null>(null)
  const [rejectRemark, setRejectRemark] = useState('')

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAdminShops({ page, size: 10, status, keyword: keyword || undefined })
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

  function handleApprove(row: ShopAdminVO) {
    setActing(row.id)
    auditShop(row.id, { status: 1 })
      .then(() => {
        message.success(`已通过「${row.shopName}」，商家身份已开通`)
        load()
      })
      .catch(() => {})
      .finally(() => setActing(null))
  }

  function handleReject() {
    if (!rejectTarget) {
      return
    }
    setActing(rejectTarget.id)
    auditShop(rejectTarget.id, { status: 2, auditRemark: rejectRemark || undefined })
      .then(() => {
        message.success('已驳回入驻申请')
        setRejectTarget(null)
        setRejectRemark('')
        load()
      })
      .catch(() => {})
      .finally(() => setActing(null))
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '店铺名称', dataIndex: 'shopName', width: 180 },
    { title: '商家', dataIndex: 'ownerUsername', width: 120, render: (v: string | null) => v || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: number) => statusTag(v)
    },
    {
      title: '店铺介绍',
      dataIndex: 'description',
      ellipsis: true,
      render: (v: string | null) => v || '-'
    },
    { title: '审核备注', dataIndex: 'auditRemark', width: 150, render: (v: string | null) => v || '-' },
    { title: '申请时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作',
      width: 150,
      render: (_: unknown, row: ShopAdminVO) =>
        row.status === 0 ? (
          <Space>
            <Popconfirm title="通过该入驻申请？" description="通过后商家将获得商家身份" onConfirm={() => handleApprove(row)}>
              <Button size="small" type="primary" loading={acting === row.id}>
                通过
              </Button>
            </Popconfirm>
            <Button size="small" danger onClick={() => setRejectTarget(row)}>
              驳回
            </Button>
          </Space>
        ) : (
          '-'
        )
    }
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Select
          style={{ width: 130 }}
          value={status}
          options={STATUS_FILTERS}
          onChange={(v) => {
            setPage(1)
            setStatus(v)
          }}
        />
        <Input.Search
          placeholder="店铺名 / 商家名"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onSearch={(v) => {
            setPage(1)
            setKeyword(v)
          }}
          style={{ width: 280 }}
        />
      </Space>
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
            pageSize: 10,
            total,
            showSizeChanger: false,
            onChange: (p) => setPage(p)
          }}
        />
      )}

      <Modal
        title={`驳回入驻申请：${rejectTarget?.shopName ?? ''}`}
        open={!!rejectTarget}
        onCancel={() => {
          setRejectTarget(null)
          setRejectRemark('')
        }}
        onOk={handleReject}
        okText="确认驳回"
        okButtonProps={{ danger: true, loading: acting === rejectTarget?.id }}
        destroyOnClose
      >
        <Input.TextArea
          rows={3}
          placeholder="驳回原因（商家可查看）"
          value={rejectRemark}
          maxLength={500}
          onChange={(e) => setRejectRemark(e.target.value)}
        />
      </Modal>
    </div>
  )
}
