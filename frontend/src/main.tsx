import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import '@ant-design/v5-patch-for-react-19'
import 'antd/dist/reset.css'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import '@/styles/global.scss'

// Vite CJS/ESM interop 下 `import zhCN` 得到的是 `{ default: locale }` 包装，需解包
const antdLocale = (zhCN as unknown as { default?: typeof zhCN }).default || zhCN

// 渲染前同步恢复会话（localStorage 同步读取），避免首帧守卫误判已登录用户
useUserStore.getState().restoreSession()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={antdLocale}>
      <RouterProvider router={router} />
    </ConfigProvider>
  </React.StrictMode>
)
