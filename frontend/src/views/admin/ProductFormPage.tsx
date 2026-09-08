import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Card, Form, Input, InputNumber, Select, Space, Switch, message } from 'antd'
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons'
import { createProduct, getAdminProduct, updateProduct } from '@/api/admin/product'
import type { ProductFormData, ProductSkuForm } from '@/api/admin/product'
import { getAdminCategories } from '@/api/admin/category'
import type { AdminCategory } from '@/api/admin/category'

export default function ProductFormPage() {
  const { id } = useParams()
  const isEdit = !!id
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [categories, setCategories] = useState<AdminCategory[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [loading, setLoading] = useState(isEdit)

  useEffect(() => {
    getAdminCategories().then(setCategories).catch(() => {})
  }, [])

  useEffect(() => {
    if (!isEdit) return
    getAdminProduct(id!)
      .then((d) => {
        form.setFieldsValue({
          name: d.name,
          title: d.title,
          brief: d.brief,
          description: d.description,
          categoryId: d.categoryId,
          brand: d.brand,
          unit: d.unit,
          price: d.price,
          originalPrice: d.originalPrice,
          stock: d.stock,
          mainImage: d.mainImage,
          isFeatured: d.isFeatured === 1,
          status: d.status === 1,
          skus: d.skus.map((s) => ({
            skuCode: s.skuCode,
            price: s.price,
            originalPrice: s.originalPrice,
            stock: s.stock,
            specs: s.specs
          }))
        })
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [id, isEdit, form])

  function buildPayload(values: Record<string, unknown>): ProductFormData {
    const skus: ProductSkuForm[] = ((values.skus as Array<Record<string, unknown>> | undefined) || []).map(
      (s: Record<string, unknown>) => ({
        skuCode: String(s.skuCode || ''),
        price: Number(s.price ?? 0),
        originalPrice: s.originalPrice != null ? Number(s.originalPrice) : undefined,
        stock: Number(s.stock ?? 0),
        specs: ((s.specs as Array<Record<string, unknown>> | undefined) || []).map((sp: Record<string, unknown>) => ({
          key: String(sp.key || ''),
          value: String(sp.value || '')
        }))
      })
    )
    return {
      name: values.name as string,
      title: values.title as string,
      brief: values.brief as string,
      description: values.description as string,
      categoryId: values.categoryId as number,
      brand: values.brand as string,
      unit: values.unit as string,
      price: Number(values.price),
      originalPrice: values.originalPrice != null ? Number(values.originalPrice) : undefined,
      stock: Number(values.stock ?? 0),
      mainImage: values.mainImage as string,
      isFeatured: values.isFeatured ? 1 : 0,
      status: values.status ? 1 : 0,
      skus
    }
  }

  async function handleSubmit(values: Record<string, unknown>) {
    setSubmitting(true)
    try {
      const payload = buildPayload(values)
      if (isEdit) {
        await updateProduct(id!, payload)
        message.success('商品已更新')
      } else {
        await createProduct(payload)
        message.success('商品已创建')
      }
      navigate('/admin/products')
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
    }
  }

  const categoryOptions = categories
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((c) => ({ value: c.id, label: `${'　'.repeat(Math.max(c.level - 1, 0))}${c.name}` }))

  return (
    <Card title={isEdit ? '编辑商品' : '新增商品'} loading={loading}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ status: true, isFeatured: false, skus: [] }} style={{ maxWidth: 720 }}>
        <Form.Item name="name" label="商品名称" rules={[{ required: true, message: '请输入商品名称' }]}>
          <Input placeholder="商品名称" />
        </Form.Item>
        <Form.Item name="categoryId" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
          <Select options={categoryOptions} placeholder="选择分类" showSearch optionFilterProp="label" />
        </Form.Item>
        <Form.Item name="title" label="卖点标题">
          <Input placeholder="卖点标题（选填）" />
        </Form.Item>
        <Form.Item name="brief" label="商品简介">
          <Input placeholder="商品简介（选填）" />
        </Form.Item>
        <Space size="large">
          <Form.Item name="brand" label="品牌">
            <Input style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="unit" label="单位">
            <Input style={{ width: 120 }} placeholder="件" />
          </Form.Item>
        </Space>
        <Space size="large">
          <Form.Item name="price" label="售价" rules={[{ required: true, message: '请输入售价' }]}>
            <InputNumber min={0.01} precision={2} style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="originalPrice" label="原价">
            <InputNumber min={0} precision={2} style={{ width: 200 }} />
          </Form.Item>
          <Form.Item name="stock" label="库存">
            <InputNumber min={0} style={{ width: 140 }} />
          </Form.Item>
        </Space>
        <Space size="large">
          <Form.Item name="mainImage" label="主图 URL">
            <Input style={{ width: 300 }} placeholder="/uploads/xxx.jpg" />
          </Form.Item>
          <Form.Item name="isFeatured" label="推荐" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="status" label="上架" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Space>
        <Form.Item name="description" label="商品详情（HTML）">
          <Input.TextArea rows={4} placeholder="商品详情 HTML" />
        </Form.Item>

        <div style={{ fontWeight: 600, marginBottom: 12 }}>SKU 规格</div>
        <Form.List name="skus">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name, ...restField }) => (
                <Card key={key} size="small" style={{ marginBottom: 12 }} title={`SKU ${name + 1}`} extra={<Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => remove(name)} />}>
                  <Space size="large" wrap>
                    <Form.Item {...restField} name={[name, 'skuCode']} label="编码" rules={[{ required: true, message: 'SKU编码必填' }]}>
                      <Input style={{ width: 160 }} placeholder="SKU编码" />
                    </Form.Item>
                    <Form.Item {...restField} name={[name, 'price']} label="价格" rules={[{ required: true, message: '价格必填' }]}>
                      <InputNumber min={0.01} precision={2} style={{ width: 140 }} />
                    </Form.Item>
                    <Form.Item {...restField} name={[name, 'stock']} label="库存">
                      <InputNumber min={0} style={{ width: 110 }} />
                    </Form.Item>
                  </Space>
                  <div style={{ marginTop: 8 }}>
                    <Form.List name={[name, 'specs']}>
                      {(specFields, { add: addSpec, remove: removeSpec }) => (
                        <>
                          {specFields.map(({ key: specKey, name: specName, ...specRest }) => (
                            <Space key={specKey} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                              <Form.Item {...specRest} name={[specName, 'key']} rules={[{ required: true, message: '规格名' }]}>
                                <Input style={{ width: 110 }} placeholder="规格名（如 颜色）" />
                              </Form.Item>
                              <Form.Item {...specRest} name={[specName, 'value']} rules={[{ required: true, message: '规格值' }]}>
                                <Input style={{ width: 140 }} placeholder="规格值（如 黑色）" />
                              </Form.Item>
                              <Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => removeSpec(specName)} />
                            </Space>
                          ))}
                          <Button type="dashed" icon={<PlusOutlined />} onClick={() => addSpec()}>
                            添加规格项
                          </Button>
                        </>
                      )}
                    </Form.List>
                  </div>
                </Card>
              ))}
              <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add({ specs: [] })}>
                添加 SKU
              </Button>
            </>
          )}
        </Form.List>

        <Space style={{ marginTop: 24 }}>
          <Button type="primary" htmlType="submit" loading={submitting}>
            保存
          </Button>
          <Button onClick={() => navigate('/admin/products')}>返回</Button>
        </Space>
      </Form>
    </Card>
  )
}
