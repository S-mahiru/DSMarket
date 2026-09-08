import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { createCategory, deleteCategory, getAdminCategories, updateCategory } from '@/api/admin/category'
import PageError from '@/components/business/PageError'
import type { AdminCategory } from '@/api/admin/category'

interface TreeNode extends AdminCategory {
  children?: TreeNode[]
}

export default function CategoryManagePage() {
  const [form] = Form.useForm()
  const [list, setList] = useState<AdminCategory[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<AdminCategory | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getAdminCategories()
      .then(setList)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const treeData = useMemo<TreeNode[]>(() => {
    const map = new Map<number, TreeNode>()
    list.forEach((c) => map.set(c.id, { ...c }))
    const roots: TreeNode[] = []
    list.forEach((c) => {
      const node = map.get(c.id)!
      if (c.parentId && map.has(c.parentId)) {
        map.get(c.parentId)!.children = [...(map.get(c.parentId)!.children || []), node]
      } else {
        roots.push(node)
      }
    })
    return roots
  }, [list])

  const parentOptions = useMemo(
    () => list.map((c) => ({ value: c.id, label: `${'　'.repeat(Math.max(c.level - 1, 0))}${c.name}` })),
    [list]
  )

  function openCreate() {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  function openEdit(row: AdminCategory) {
    setEditing(row)
    form.setFieldsValue({ name: row.name, parentId: row.parentId || undefined, sortOrder: row.sortOrder, status: row.status === 1 })
    setModalOpen(true)
  }

  function handleSubmit() {
    form.validateFields().then((values) => {
      setSubmitting(true)
      const payload = {
        name: values.name,
        parentId: values.parentId || 0,
        sortOrder: values.sortOrder ?? 0,
        status: values.status ? 1 : 0
      }
      const req = editing ? updateCategory(editing.id, payload) : createCategory(payload)
      req
        .then(() => {
          message.success(editing ? '分类已更新' : '分类已创建')
          setModalOpen(false)
          load()
        })
        .catch(() => {})
        .finally(() => setSubmitting(false))
    })
  }

  function handleDelete(row: AdminCategory) {
    deleteCategory(row.id)
      .then(() => {
        message.success('已删除')
        load()
      })
      .catch(() => {})
  }

  const columns = [
    { title: '分类名称', dataIndex: 'name' },
    { title: '层级', dataIndex: 'level', width: 80 },
    { title: '排序', dataIndex: 'sortOrder', width: 80 },
    { title: '状态', dataIndex: 'status', width: 90, render: (v: number) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag>隐藏</Tag>) },
    {
      title: '操作',
      width: 140,
      render: (_: unknown, row: AdminCategory) => (
        <Space>
          <Button size="small" type="link" onClick={() => openEdit(row)}>
            编辑
          </Button>
          <Popconfirm title="确认删除该分类？" onConfirm={() => handleDelete(row)}>
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
      <div style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增分类
        </Button>
      </div>
      {error ? (
        <PageError onRetry={load} />
      ) : (
        <Table rowKey="id" loading={loading} columns={columns} dataSource={treeData} pagination={false} expandable={{ defaultExpandAllRows: true }} />
      )}

      <Modal title={editing ? '编辑分类' : '新增分类'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={handleSubmit} confirmLoading={submitting}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]}>
            <Input placeholder="分类名称" />
          </Form.Item>
          <Form.Item name="parentId" label="父分类">
            <Select options={parentOptions} allowClear placeholder="顶级分类" showSearch optionFilterProp="label" />
          </Form.Item>
          <Space size="large">
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="status" label="启用" valuePropName="checked" initialValue={true}>
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  )
}
