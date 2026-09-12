import { Link, Outlet, useNavigate } from 'react-router-dom'
import { Badge, Dropdown } from 'antd'
import { ShoppingCartOutlined } from '@ant-design/icons'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import AiSupportWidget from '@/components/ai/AiSupportWidget'
import './MainLayout.scss'

export default function MainLayout() {
  const navigate = useNavigate()
  const isLoggedIn = useUserStore((s) => s.isLoggedIn)
  const isAdmin = useUserStore((s) => s.isAdmin)
  const isMerchant = useUserStore((s) => s.isMerchant)
  const nickname = useUserStore((s) => s.nickname)
  const logout = useUserStore((s) => s.logout)
  const cartCount = useCartStore((s) => s.count)

  // 商家入口按 role 三选一（REQ-20260912 §4.2）。**ADMIN 一个都不给**：
  // 入驻会被后端直接 400「管理员无需入驻」（ShopServiceImpl:38-40），商家中心会被
  // `/merchant/**` 的 hasRole("MERCHANT") 挡成 403 —— 给入口等于给一个必然失败的按钮。
  const shopEntry = isAdmin
    ? []
    : isMerchant
      ? [{ key: 'merchant', label: '商家中心', onClick: () => navigate('/merchant') }]
      : [{ key: 'merchantApply', label: '商家入驻', onClick: () => navigate('/merchant/apply') }]

  const userMenuItems = [
    { key: 'profile', label: '个人中心', onClick: () => navigate('/profile') },
    { key: 'orders', label: '我的订单', onClick: () => navigate('/orders') },
    ...shopEntry,
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
      {/* 放在 </main> **之外**：挂在 main-content 里会继承它的内边距与溢出，
          悬浮定位会跟着内容偏移。这个位置也天然等于"买家面"——登录/注册走 BlankLayout、
          后台走 AdminLayout，都不会渲染到这里，所以不需要维护路由白名单。 */}
      <AiSupportWidget />
    </div>
  )
}
