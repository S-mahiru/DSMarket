import { Link } from 'react-router-dom'

export default function NotFoundPage() {
  return (
    <div className="page-placeholder">
      <h1>404</h1>
      <p>页面不存在</p>
      <Link to="/">返回首页</Link>
    </div>
  )
}
