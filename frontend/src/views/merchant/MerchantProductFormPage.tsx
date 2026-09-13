import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Card, Form, Input, InputNumber, Select, Space, Switch, message } from 'antd'
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons'
import {
  createMerchantProduct,
  getMerchantProduct,
  updateMerchantProduct
} from '@/api/merchant/product'
import type { ProductFormData, ProductSkuForm } from '@/types/product'
import { getCategories } from '@/api/product'
import type { CategoryNode } from '@/types/api'

/** 拉平后的分类选项（下拉只认平表，见 `flattenCategories`） */
interface FlatCategory {
  id: number
  name: string
  level: number
}

/**
 * 把分类树按**遍历序**拉平成下拉选项。
 *
 * 不要再按 `sortOrder` 排序：`CategoryServiceImpl.getTree()` 查库时已经
 * `orderByAsc(sortOrder)`，`buildTree()` 保持该顺序装配 —— 遍历序**就是** sortOrder 序。
 * （`CategoryNode` 上也没有 `sortOrder` 字段，想排也排不了。）
 *
 * 另注意**不要就地 sort**：直接对 state 数组调 `.sort()` 会改到 React 的 state 本身。
 */
function flattenCategories(nodes: CategoryNode[]): FlatCategory[] {
  const flat: FlatCategory[] = []
  const walk = (list: CategoryNode[]) => {
    for (const node of list) {
      flat.push({ id: node.id, name: node.name, level: node.level })
      if (node.children?.length) walk(node.children)
    }
  }
  walk(nodes)
  return flat
}

/**
 * 商家新建/编辑商品（REQ-20260912 §4.6，字段沿用 `ProductFormDTO`）。
 *
 * 与 `admin/ProductFormPage` 同构，仅数据源换成 `/merchant/products`。
 * **表单里没有归属字段**：谁的商品由登录态决定，提交时后端按自家店铺强制赋值（§4.4 硬规则 1）。
 *
 * 分类选项走**公开接口** `getCategories()`（`/api/v1/categories`，`SecurityConfig` 中 permitAll）：
 * 分类是平台维护的公共字典，商家只读、不新建。
 *
 * **这里曾经调 `getAdminCategories()`**（实际打 `/api/v1/admin/categories`），被
 * `hasRole("ADMIN")` 拦成 403；而 403 又被 `.catch(() => {})` 吞掉，表现为「分类下拉是空的」。
 * 分类是必填项，商家因此**根本建不了商品**。见 REQ-20260913-既有缺陷修复 §2.1。
 */
export default function MerchantProductFormPage() {
  const { id } = useParams()
  const isEdit = !!id
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [categories, setCategories] = useState<CategoryNode[]>([])
  /** 编辑模式下商品当前的分类：它可能已被停用，需补进选项（见下方选项装配） */
  const [currentCategory, setCurrentCategory] = useState<{ id: number; name: string } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [loading, setLoading] = useState(isEdit)

  useEffect(() => {
    // 失败时拦截器已弹提示；这里只吞掉 rejection，避免 unhandled rejection
    getCategories().then(setCategories).catch(() => {})
  }, [])

  useEffect(() => {
    if (!isEdit) return
    getMerchantProduct(id!)
      .then((d) => {
        setCurrentCategory({ id: d.categoryId, name: d.categoryName })
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
        specs: ((s.specs as Array<Record<string, unknown>> | undefined) || []).map(
          (sp: Record<string, unknown>) => ({
            key: String(sp.key || ''),
            value: String(sp.value || '')
          })
        )
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
        await updateMerchantProduct(id!, payload)
        message.success('商品已更新')
      } else {
        await createMerchantProduct(payload)
        message.success('商品已创建')
      }
      navigate('/merchant/products')
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
    }
  }

  const categoryOptions = flattenCategories(categories).map((c) => ({
    value: c.id,
    label: `${'　'.repeat(Math.max(c.level - 1, 0))}${c.name}`
  }))

  // 公开接口只返回启用中的分类。若该商品所属分类已被停用，选项里就没有它的当前值，
  // Select 会显示裸 id（看起来像 bug）—— 故把当前分类补回去并标注「已停用」。
  if (currentCategory && !categoryOptions.some((o) => o.value === currentCategory.id)) {
    categoryOptions.push({ value: currentCategory.id, label: `${currentCategory.name}（已停用）` })
  }

  return (
    <Card title={isEdit ? '编辑商品' : '新建商品'} loading={loading}>
      <Form
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        initialValues={{ status: true, isFeatured: false, skus: [] }}
        style={{ maxWidth: 720 }}
      >
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
                <Card
                  key={key}
                  size="small"
                  style={{ marginBottom: 12 }}
                  title={`SKU ${name + 1}`}
                  extra={
                    <Button
                      type="text"
                      danger
                      icon={<MinusCircleOutlined />}
                      onClick={() => remove(name)}
                    />
                  }
                >
                  <Space size="large" wrap>
                    <Form.Item
                      {...restField}
                      name={[name, 'skuCode']}
                      label="编码"
                      rules={[{ required: true, message: 'SKU编码必填' }]}
                    >
                      <Input style={{ width: 160 }} placeholder="SKU编码" />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'price']}
                      label="价格"
                      rules={[{ required: true, message: '价格必填' }]}
                    >
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
                            <Space
                              key={specKey}
                              style={{ display: 'flex', marginBottom: 8 }}
                              align="baseline"
                            >
                              <Form.Item
                                {...specRest}
                                name={[specName, 'key']}
                                rules={[{ required: true, message: '规格名' }]}
                              >
                                <Input style={{ width: 110 }} placeholder="规格名（如 颜色）" />
                              </Form.Item>
                              <Form.Item
                                {...specRest}
                                name={[specName, 'value']}
                                rules={[{ required: true, message: '规格值' }]}
                              >
                                <Input style={{ width: 140 }} placeholder="规格值（如 黑色）" />
                              </Form.Item>
                              <Button
                                type="text"
                                danger
                                icon={<MinusCircleOutlined />}
                                onClick={() => removeSpec(specName)}
                              />
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
          <Button onClick={() => navigate('/merchant/products')}>返回</Button>
        </Space>
      </Form>
    </Card>
  )
}
