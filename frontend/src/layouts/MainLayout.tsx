import { Link, Outlet, useNavigate } from 'react-router-dom'
import { Badge, Dropdown } from 'antd'
import { ShoppingCartOutlined } from '@ant-design/icons'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import './MainLayout.scss'

export default function MainLayout() {
  const navigate = useNavigate()
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const isAdmin = useUserStore((s) => s.isAdmin)
  const isMerchant = useUserStore((s) => s.isMerchant)
  const nickname = useUserStore((s) => s.nickname)
  const logout = useUserStore((s) => s.logout)
  const cartCount = useCartStore((s) => s.count)

  const userMenuItems = [
    { key: 'profile', label: '个人中心', onClick: () => navigate('/profile') },
    { key: 'orders', label: '我的订单', onClick: () => navigate('/orders') },
    ...(isMerchant
      ? [{ key: 'merchant', label: '商家中心', onClick: () => navigate('/merchant') }]
      : [{ key: 'merchantApply', label: '商家入驻', onClick: () => navigate('/merchant/apply') }]),
    ...(isAdmin ? [{ key: 'admin', label: '管理后台', onClick: () => navigate('/admin') }] : []),
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', onClick: () => logout() }
  ]

  return (
    <div className="main-layout">
      <header className="app-header">
        <div className="header-inner">
          <Link to="/" className="logo">黑海商城</Link>
          <nav className="main-nav">
            <Link to="/products">全部商品</Link>
          </nav>
          <div className="header-actions">
            {isLoggedIn && (
              <Link to="/cart" className="cart-link">
                <Badge count={cartCount} showZero={false}>
                  <ShoppingCartOutlined style={{ fontSize: 20 }} />
                </Badge>
              </Link>
            )}
            {isLoggedIn ? (
              <Dropdown menu={{ items: userMenuItems }} trigger={['click']}>
                <span className="user-dropdown">{nickname}</span>
              </Dropdown>
            ) : (
              <>
                <Link to="/login" className="btn-login">登录</Link>
                <Link to="/register" className="btn-register">注册</Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
