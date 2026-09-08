import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Card, Form, Input, message } from 'antd'
import { useUserStore } from '@/stores/user'
import './auth.scss'

interface RegisterForm {
  username: string
  password: string
  confirmPassword: string
  email?: string
  phone?: string
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const register = useUserStore((s) => s.register)
  const [loading, setLoading] = useState(false)

  async function onFinish(values: RegisterForm) {
    setLoading(true)
    try {
      await register({
        username: values.username,
        password: values.password,
        email: values.email,
        phone: values.phone
      })
      message.success('注册成功，请登录')
      navigate('/login')
    } catch {
      // 错误提示由 axios 拦截器统一处理
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <Card className="auth-card">
        <h2 className="auth-title">注册账号</h2>
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '4-20位字母/数字/下划线' }
            ]}
          >
            <Input placeholder="4-20位字母/数字/下划线" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, max: 32, message: '密码长度6-32位' }
            ]}
          >
            <Input.Password placeholder="6-32位密码" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认密码"
            dependencies={['password']}
            rules={[
              { required: true, message: '请再次输入密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) {
                    return Promise.resolve()
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'))
                }
              })
            ]}
          >
            <Input.Password placeholder="再次输入密码" />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
            <Input placeholder="邮箱（选填）" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            注册
          </Button>
          <div className="auth-links">
            已有账号？<Link to="/login">去登录</Link>
          </div>
        </Form>
      </Card>
    </div>
  )
}
