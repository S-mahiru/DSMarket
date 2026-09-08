import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Button, Card, Form, Input, message } from 'antd'
import { useUserStore } from '@/stores/user'
import './auth.scss'

interface LoginForm {
  username: string
  password: string
}

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useUserStore((s) => s.login)
  const [loading, setLoading] = useState(false)

  // 守卫重定向时携带的来源页
  const redirect = (location.state as { redirect?: string } | null)?.redirect || '/'

  async function onFinish(values: LoginForm) {
    setLoading(true)
    try {
      await login(values)
      message.success('登录成功')
      navigate(redirect, { replace: true })
    } catch {
      // 错误提示由 axios 拦截器统一处理
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <Card className="auth-card">
        <h2 className="auth-title">黑海商城</h2>
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
          <div className="auth-links">
            还没有账号？<Link to="/register">去注册</Link>
          </div>
        </Form>
      </Card>
    </div>
  )
}
