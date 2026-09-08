import { useCallback, useEffect, useState } from 'react'
import { Input, Space, Switch, Table, Tag, message } from 'antd'
import { getAdminUsers, updateUserStatus } from '@/api/admin/user'
import PageError from '@/components/business/PageError'
import { useUserStore } from '@/stores/user'
import type { UserAdminVO } from '@/types/admin'

export default function UserManagePage() {
  const currentUserId = useUserStore((s) => s.userInfo?.id)
  const [data, setData] = useState<UserAdminVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [toggling, setToggling] = useState<number | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAdminUsers({ page, size: 10, keyword: keyword || undefined })
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

  function handleToggle(row: UserAdminVO, checked: boolean) {
    setToggling(row.id)
    updateUserStatus(row.id, checked ? 1 : 0)
      .then(() => {
        message.success(checked ? '已启用' : '已禁用')
        load()
      })
      .catch(() => {})
      .finally(() => setToggling(null))
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '用户名', dataIndex: 'username', width: 140 },
    { title: '昵称', dataIndex: 'nickname', width: 140, render: (v: string | null) => v || '-' },
    { title: '手机号', dataIndex: 'phone', width: 130, render: (v: string | null) => v || '-' },
    { title: '邮箱', dataIndex: 'email', render: (v: string | null) => v || '-' },
    {
      title: '角色',
      dataIndex: 'role',
      width: 90,
      render: (v: string) => (v === 'ADMIN' ? <Tag color="red">管理员</Tag> : <Tag color="blue">用户</Tag>)
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: number, row: UserAdminVO) => (
        <Switch
          checked={v === 1}
          disabled={row.id === currentUserId}
          loading={toggling === row.id}
          onChange={(checked) => handleToggle(row, checked)}
        />
      )
    },
    { title: '注册时间', dataIndex: 'createdAt', width: 170 },
    { title: '最近登录', dataIndex: 'lastLoginTime', width: 170, render: (v: string | null) => v || '-' }
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="用户名 / 昵称 / 手机号"
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
    </div>
  )
}
