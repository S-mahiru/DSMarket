# React 19 + TypeScript + Vite

DSMarket 黑海商城前端，基于 React 19 + TypeScript + Vite。

## 技术栈

- **框架**: React 19 (Hooks)
- **构建**: Vite
- **UI 组件库**: Ant Design (antd) 5.x
- **状态管理**: Zustand 5.x
- **路由**: React Router 7.x
- **HTTP**: Axios

## 常用命令

```bash
npm run dev      # 启动开发服务器 (localhost:5173)
npm run build    # 类型检查 + 生产构建
npm run preview  # 预览构建产物
```

## 目录结构

```
src/
├── api/          # Axios 请求层
├── components/   # 公共组件
├── hooks/        # React Hooks
├── layouts/      # 布局组件
├── router/       # 路由配置与守卫
├── stores/       # Zustand 状态管理
├── types/        # TypeScript 类型
├── views/        # 页面
└── styles/       # 全局样式
```
