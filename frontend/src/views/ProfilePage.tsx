import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, Descriptions, Form, Input, Modal, Space, Tag, message } from 'antd'
import { useUserStore } from '@/stores/user'
import { updateProfile, updatePassword } from '@/api/user'

interface PasswordForm {
  oldPassword: string
  newPassword: string
}

export default function ProfilePage() {
  const userInfo = useUserStore((s) => s.userInfo)
  const isAdmin = useUserStore((s) => s.isAdmin)
  const fetchProfile = useUserStore((s) => s.fetchProfile)

  const [passwordOpen, setPasswordOpen] = useState(false)
  const [editNickname, setEditNickname] = useState(false)
  const [nickname, setNickname] = useState(userInfo?.nickname || '')
  const [submitting, setSubmitting] = useState(false)

  async function handleChangePassword(values: PasswordForm) {
    setSubmitting(true)
    try {
      await updatePassword(values)
      message.success('密码修改成功')
      setPasswordOpen(false)
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
    }
  }

  async function handleSaveNickname() {
    setSubmitting(true)
    try {
      await updateProfile({ nickname })
      await fetchProfile()
      message.success('昵称已更新')
      setEditNickname(false)
    } catch {
      // 拦截器已提示
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ maxWidth: 720, margin: '24px auto' }}>
      <Card title="个人中心" style={{ marginBottom: 16 }}>
        <Descriptions column={1} bordered size="middle">
          <Descriptions.Item label="用户名">{userInfo?.username}</Descriptions.Item>
          <Descriptions.Item label="昵称">
            {editNickname ? (
              <Space>
                <Input value={nickname} onChange={(e) => setNickname(e.target.value)} style={{ width: 160 }} />
                <Button size="small" type="primary" loading={submitting} onClick={handleSaveNickname}>
                  保存
                </Button>
                <Button size="small" onClick={() => setEditNickname(false)}>
                  取消
                </Button>
              </Space>
            ) : (
              <Space>
                {userInfo?.nickname || '-'}
                <Button size="small" type="link" onClick={() => setEditNickname(true)}>
                  修改
                </Button>
              </Space>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="邮箱">{userInfo?.email || '-'}</Descriptions.Item>
          <Descriptions.Item label="手机号">{userInfo?.phone || '-'}</Descriptions.Item>
          <Descriptions.Item label="角色">
            {isAdmin ? <Tag color="red">管理员</Tag> : <Tag color="blue">普通用户</Tag>}
          </Descriptions.Item>
          <Descriptions.Item label="注册时间">{userInfo?.createdAt || '-'}</Descriptions.Item>
        </Descriptions>
        <Button style={{ marginTop: 16 }} onClick={() => setPasswordOpen(true)}>
          修改密码
        </Button>
      </Card>

      <Card title="我的交易" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <Link to="/orders">我的订单</Link>
          <Link to="/addresses">收货地址</Link>
        </Space>
      </Card>

      <Modal
        title="修改密码"
        open={passwordOpen}
        onCancel={() => setPasswordOpen(false)}
        footer={null}
      >
        <Form layout="vertical" onFinish={handleChangePassword}>
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password placeholder="原密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, max: 32, message: '密码长度6-32位' }
            ]}
          >
            <Input.Password placeholder="6-32位新密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            确认修改
          </Button>
        </Form>
      </Modal>
    </div>
  )
}
