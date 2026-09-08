import { useEffect } from 'react'
import { Form, Input, Modal, Switch } from 'antd'
import type { Address } from '@/types/address'

interface Props {
  open: boolean
  editing: Address | null
  onCancel: () => void
  onSubmit: (values: Partial<Address>) => void
  submitting?: boolean
}

export default function AddressForm({ open, editing, onCancel, onSubmit, submitting }: Props) {
  const [form] = Form.useForm()

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          receiverName: editing.receiverName,
          receiverPhone: editing.receiverPhone,
          province: editing.province,
          city: editing.city,
          district: editing.district,
          detailAddress: editing.detailAddress,
          zipCode: editing.zipCode,
          label: editing.label,
          isDefault: editing.isDefault === 1
        })
      } else {
        form.resetFields()
      }
    }
  }, [open, editing, form])

  return (
    <Modal
      title={editing ? '编辑地址' : '新增地址'}
      open={open}
      onCancel={onCancel}
      onOk={() => form.submit()}
      confirmLoading={submitting}
    >
      <Form form={form} layout="vertical" onFinish={onSubmit}>
        <Form.Item name="receiverName" label="收货人" rules={[{ required: true, message: '请输入收货人' }]}>
          <Input placeholder="收货人姓名" />
        </Form.Item>
        <Form.Item name="receiverPhone" label="手机号" rules={[{ required: true, message: '请输入手机号' }, { pattern: /^1\d{10}$/, message: '手机号格式不正确' }]}>
          <Input placeholder="11位手机号" maxLength={11} />
        </Form.Item>
        <Form.Item name="province" label="省份" rules={[{ required: true, message: '请输入省份' }]}>
          <Input placeholder="省份" />
        </Form.Item>
        <Form.Item name="city" label="城市" rules={[{ required: true, message: '请输入城市' }]}>
          <Input placeholder="城市" />
        </Form.Item>
        <Form.Item name="district" label="区/县" rules={[{ required: true, message: '请输入区/县' }]}>
          <Input placeholder="区/县" />
        </Form.Item>
        <Form.Item name="detailAddress" label="详细地址" rules={[{ required: true, message: '请输入详细地址' }]}>
          <Input placeholder="街道、门牌号等" />
        </Form.Item>
        <Form.Item name="zipCode" label="邮编">
          <Input placeholder="邮编（选填）" maxLength={6} />
        </Form.Item>
        <Form.Item name="label" label="标签">
          <Input placeholder="如：家 / 公司 / 学校" maxLength={20} />
        </Form.Item>
        <Form.Item name="isDefault" label="设为默认" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  )
}
