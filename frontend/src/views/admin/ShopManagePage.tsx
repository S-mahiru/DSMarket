import { useCallback, useEffect, useState } from 'react'
import { Button, Input, Modal, Popconfirm, Select, Space, Table, Tag, message } from 'antd'
import { auditShop, closeShop, getAdminShops } from '@/api/admin/shop'
import PageError from '@/components/business/PageError'
import { SHOP_STATUS } from '@/types/shop'
import type { ShopAdminVO } from '@/types/shop'

const STATUS_FILTERS = [
  { value: undefined, label: '全部' },
  { value: SHOP_STATUS.PENDING, label: '待审核' },
  { value: SHOP_STATUS.OPEN, label: '已开通' },
  { value: SHOP_STATUS.REJECTED, label: '已驳回' },
  { value: SHOP_STATUS.CLOSED, label: '已关闭' }
]

// 「已驳回」与「已关闭」是**两个不同状态**（REQ-20260913-店铺关闭能力 §4.2 方案甲）：
// 驳回可改资料重来，关闭是终局。文案与颜色都必须分开，否则管理员会以为关掉的店还能再申请。
function statusTag(status: number) {
  const map: Record<number, { color: string; label: string }> = {
    [SHOP_STATUS.PENDING]: { color: 'orange', label: '待审核' },
    [SHOP_STATUS.OPEN]: { color: 'green', label: '已开通' },
    [SHOP_STATUS.REJECTED]: { color: 'red', label: '已驳回' },
    [SHOP_STATUS.CLOSED]: { color: 'default', label: '已关闭' }
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
  const [closeTarget, setCloseTarget] = useState<ShopAdminVO | null>(null)
  const [closeRemark, setCloseRemark] = useState('')

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

  function handleClose() {
    if (!closeTarget) {
      return
    }
    setActing(closeTarget.id)
    closeShop(closeTarget.id, { auditRemark: closeRemark })
      .then(() => {
        message.success(`已关闭「${closeTarget.shopName}」，该店商品已全站下架`)
        setCloseTarget(null)
        setCloseRemark('')
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
      render: (_: unknown, row: ShopAdminVO) => {
        if (row.status === SHOP_STATUS.PENDING) {
          return (
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
          )
        }
        if (row.status === SHOP_STATUS.OPEN) {
          return (
            <Button size="small" danger onClick={() => setCloseTarget(row)}>
              关闭
            </Button>
          )
        }
        // 已驳回 / 已关闭：没有可执行的动作（关闭是终局，Q2 拍板不做重开）
        return '-'
      }
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

      {/* 关闭是**不可撤销**的（Q2 拍板不做重开），所以这里比驳回多两层提示：
          弹窗正文写明后果，且理由必填 —— 理由会写进 audit_remark，商家在商家中心看得到。 */}
      <Modal
        title={`关闭店铺：${closeTarget?.shopName ?? ''}`}
        open={!!closeTarget}
        onCancel={() => {
          setCloseTarget(null)
          setCloseRemark('')
        }}
        onOk={handleClose}
        okText="确认关闭"
        okButtonProps={{ danger: true, disabled: !closeRemark.trim(), loading: acting === closeTarget?.id }}
        destroyOnClose
      >
        <p style={{ marginTop: 0 }}>
          关闭后该店铺的商品将<b>全站不可见</b>（列表、首页推荐、详情、店铺页），且<b>不可撤销</b>——
          商家也不能重新提交入驻申请。
        </p>
        <Input.TextArea
          rows={3}
          placeholder="关闭理由（必填，商家可查看）"
          value={closeRemark}
          maxLength={500}
          onChange={(e) => setCloseRemark(e.target.value)}
        />
      </Modal>
    </div>
  )
}
