import { createBrowserRouter, Navigate } from 'react-router-dom'
import App from '@/App'
import BlankLayout from '@/layouts/BlankLayout'
import MainLayout from '@/layouts/MainLayout'
import AdminLayout from '@/layouts/AdminLayout'
import { RequireAuth, GuestGuard } from '@/router/guards'

// 页面组件
import HomePage from '@/views/HomePage'
import LoginPage from '@/views/LoginPage'
import RegisterPage from '@/views/RegisterPage'
import ProfilePage from '@/views/ProfilePage'
import ProductListPage from '@/views/ProductListPage'
import ProductDetailPage from '@/views/ProductDetailPage'
import CartPage from '@/views/CartPage'
import AddressListPage from '@/views/AddressListPage'
import OrderListPage from '@/views/OrderListPage'
import OrderDetailPage from '@/views/OrderDetailPage'
import ConfirmOrderPage from '@/views/ConfirmOrderPage'
import PaymentPage from '@/views/PaymentPage'
import PaymentResultPage from '@/views/PaymentResultPage'
import NotFoundPage from '@/views/NotFoundPage'
import DashboardPage from '@/views/admin/DashboardPage'
import ProductManagePage from '@/views/admin/ProductManagePage'
import ProductFormPage from '@/views/admin/ProductFormPage'
import CategoryManagePage from '@/views/admin/CategoryManagePage'
import OrderManagePage from '@/views/admin/OrderManagePage'
import UserManagePage from '@/views/admin/UserManagePage'
import ShopManagePage from '@/views/admin/ShopManagePage'
import MerchantApplyPage from '@/views/MerchantApplyPage'
import MerchantCenterPage from '@/views/MerchantCenterPage'

const router = createBrowserRouter([
  {
    // 根组件：会话恢复 + 页面标题
    element: <App />,
    children: [
      // 空白布局（登录/注册）
      {
        element: <BlankLayout />,
        children: [
          {
            path: '/login',
            element: (
              <GuestGuard>
                <LoginPage />
              </GuestGuard>
            ),
            handle: { title: '登录' }
          },
          {
            path: '/register',
            element: (
              <GuestGuard>
                <RegisterPage />
              </GuestGuard>
            ),
            handle: { title: '注册' }
          }
        ]
      },

      // 前台主布局
      {
        element: <MainLayout />,
        children: [
          { index: true, element: <HomePage />, handle: { title: '首页' } },
          { path: 'products', element: <ProductListPage />, handle: { title: '商品列表' } },
          { path: 'product/:id', element: <ProductDetailPage />, handle: { title: '商品详情' } },

          // 需认证路由
          {
            path: 'cart',
            element: (
              <RequireAuth>
                <CartPage />
              </RequireAuth>
            ),
            handle: { title: '购物车' }
          },
          {
            path: 'orders',
            element: (
              <RequireAuth>
                <OrderListPage />
              </RequireAuth>
            ),
            handle: { title: '我的订单' }
          },
          {
            path: 'order/:orderNo',
            element: (
              <RequireAuth>
                <OrderDetailPage />
              </RequireAuth>
            ),
            handle: { title: '订单详情' }
          },
          {
            path: 'checkout',
            element: (
              <RequireAuth>
                <ConfirmOrderPage />
              </RequireAuth>
            ),
            handle: { title: '确认订单' }
          },
          {
            path: 'payment/:orderNo',
            element: (
              <RequireAuth>
                <PaymentPage />
              </RequireAuth>
            ),
            handle: { title: '订单支付' }
          },
          {
            path: 'payment/result/:orderNo',
            element: (
              <RequireAuth>
                <PaymentResultPage />
              </RequireAuth>
            ),
            handle: { title: '支付结果' }
          },
          {
            path: 'addresses',
            element: (
              <RequireAuth>
                <AddressListPage />
              </RequireAuth>
            ),
            handle: { title: '收货地址' }
          },
          {
            path: 'profile',
            element: (
              <RequireAuth>
                <ProfilePage />
              </RequireAuth>
            ),
            handle: { title: '个人中心' }
          },
          {
            path: 'merchant/apply',
            element: (
              <RequireAuth>
                <MerchantApplyPage />
              </RequireAuth>
            ),
            handle: { title: '商家入驻' }
          },
          {
            path: 'merchant',
            element: (
              <RequireAuth role="MERCHANT">
                <MerchantCenterPage />
              </RequireAuth>
            ),
            handle: { title: '商家中心' }
          }
        ]
      },

      // 后台管理（需 ADMIN 角色）
      {
        path: '/admin',
        element: (
          <RequireAuth role="ADMIN">
            <AdminLayout />
          </RequireAuth>
        ),
        children: [
          { index: true, element: <Navigate to="dashboard" replace /> },
          { path: 'dashboard', element: <DashboardPage />, handle: { title: '数据概览' } },
          { path: 'products', element: <ProductManagePage />, handle: { title: '商品管理' } },
          { path: 'products/create', element: <ProductFormPage />, handle: { title: '新增商品' } },
          { path: 'products/:id/edit', element: <ProductFormPage />, handle: { title: '编辑商品' } },
          { path: 'categories', element: <CategoryManagePage />, handle: { title: '分类管理' } },
          { path: 'orders', element: <OrderManagePage />, handle: { title: '订单管理' } },
          { path: 'users', element: <UserManagePage />, handle: { title: '用户管理' } },
          { path: 'shops', element: <ShopManagePage />, handle: { title: '店铺审核' } }
        ]
      },

      // 404
      { path: '*', element: <NotFoundPage />, handle: { title: '404' } }
    ]
  }
])

export default router
