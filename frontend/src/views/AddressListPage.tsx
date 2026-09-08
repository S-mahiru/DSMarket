import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Popconfirm, Space, Tag, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { createAddress, deleteAddress, getAddresses, setDefaultAddress, updateAddress } from '@/api/address'
import type { Address } from '@/types/address'
import AddressForm from '@/components/business/AddressForm'
import PageError from '@/components/business/PageError'
import './AddressListPage.scss'

export default function AddressListPage() {
  const [list, setList] = useState<Address[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Address | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAddresses()
      .then(setList)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  function handleSubmit(values: Partial<Address>) {
    setSubmitting(true)
    const req = editing ? updateAddress(editing.id, values) : createAddress(values)
    req
      .then(() => {
        message.success(editing ? '地址已更新' : '地址已添加')
        setModalOpen(false)
        load()
      })
      .catch(() => {})
      .finally(() => setSubmitting(false))
  }

  function handleSetDefault(address: Address) {
    setDefaultAddress(address.id)
      .then(() => load())
      .catch(() => {})
  }

  function handleDelete(address: Address) {
    deleteAddress(address.id)
      .then(() => {
        message.success('地址已删除')
        load()
      })
      .catch(() => {})
  }

  return (
    <div className="address-page">
      <div className="address-header">
        <h2>收货地址</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setModalOpen(true) }}>
          新增地址
        </Button>
      </div>

      {error ? (
        <PageError onRetry={load} />
      ) : loading ? (
        <div className="page-loading">加载中...</div>
      ) : list.length === 0 ? (
        <Empty description="还没有收货地址" style={{ padding: '60px 0' }} />
      ) : (
        <div className="address-list">
          {list.map((address) => (
            <Card key={address.id} className="address-card">
              <div className="address-main">
                <div className="address-name">
                  {address.receiverName}
                  <span className="address-phone">{address.receiverPhone}</span>
                  {address.label && <Tag className="address-label">{address.label}</Tag>}
                  {address.isDefault === 1 && <Tag color="red">默认</Tag>}
                </div>
                <div className="address-detail">
                  {address.province} {address.city} {address.district} {address.detailAddress}
                </div>
              </div>
              <Space className="address-actions">
                {address.isDefault !== 1 && (
                  <Button size="small" type="link" onClick={() => handleSetDefault(address)}>
                    设为默认
                  </Button>
                )}
                <Button size="small" type="link" onClick={() => { setEditing(address); setModalOpen(true) }}>
                  编辑
                </Button>
                <Popconfirm title="确认删除该地址？" onConfirm={() => handleDelete(address)}>
                  <Button size="small" type="link" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            </Card>
          ))}
        </div>
      )}

      <AddressForm open={modalOpen} editing={editing} onCancel={() => setModalOpen(false)} onSubmit={handleSubmit} submitting={submitting} />
    </div>
  )
}
