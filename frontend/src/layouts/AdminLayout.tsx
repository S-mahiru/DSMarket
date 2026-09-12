import { Layout, Menu, Dropdown } from 'antd'
import {
  CustomerServiceOutlined,
  DashboardOutlined,
  ShopOutlined,
  ShoppingOutlined,
  TagsOutlined,
  UnorderedListOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import './AdminLayout.scss'

const { Sider, Header, Content } = Layout

const MENU_ITEMS = [
  { key: '/admin/dashboard', icon: <DashboardOutlined />, label: '数据概览' },
  { key: '/admin/products', icon: <ShoppingOutlined />, label: '商品管理' },
  { key: '/admin/categories', icon: <TagsOutlined />, label: '分类管理' },
  { key: '/admin/orders', icon: <UnorderedListOutlined />, label: '订单管理' },
  { key: '/admin/users', icon: <UserOutlined />, label: '用户管理' },
  { key: '/admin/shops', icon: <ShopOutlined />, label: '店铺审核' },
  // key 必须**等于路由前缀**：headerTitle 用 `startsWith` 反查、selectedKeys 是精确匹配，
  // 所以工作台保持单层扁平路由（`/admin/ai/support`），不要往下再分子路由。
  { key: '/admin/ai/support', icon: <CustomerServiceOutlined />, label: 'AI客服工作台' }
]

export default function AdminLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const sidebarCollapsed = useAppStore((s) => s.sidebarCollapsed)
  const toggleSidebar = useAppStore((s) => s.toggleSidebar)
  const nickname = useUserStore((s) => s.nickname)
  const logout = useUserStore((s) => s.logout)

  const headerTitle = MENU_ITEMS.find((item) => location.pathname.startsWith(item.key))?.label || ''

  const userMenuItems = [
    { key: 'home', label: '返回前台', onClick: () => navigate('/') },
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', onClick: () => logout() }
  ]

  return (
    <Layout className="admin-layout">
      <Sider width={sidebarCollapsed ? 64 : 220} collapsed={sidebarCollapsed} className="admin-sidebar">
        <div className="admin-logo">{sidebarCollapsed ? '黑海' : '黑海商城'}</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="admin-header">
          <span className="collapse-btn" onClick={toggleSidebar}>
            {sidebarCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <span className="header-title">{headerTitle}</span>
          <div className="header-right">
            <Dropdown menu={{ items: userMenuItems }} trigger={['click']}>
              <span className="user-info">{nickname}</span>
            </Dropdown>
          </div>
        </Header>
        <Content className="admin-main">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
