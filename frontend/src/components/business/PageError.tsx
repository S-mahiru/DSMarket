import { Button, Result } from 'antd'

interface PageErrorProps {
  /** 错误描述，默认“数据加载失败” */
  description?: string
  /** 点击重试回调 */
  onRetry?: () => void
}

/** 页面级 Error 占位：加载失败时展示，提供重试入口（三态之一） */
export default function PageError({ description = '数据加载失败，请稍后重试', onRetry }: PageErrorProps) {
  return (
    <Result
      status="error"
      title="出错了"
      subTitle={description}
      extra={
        onRetry ? (
          <Button type="primary" onClick={onRetry}>
            重试
          </Button>
        ) : undefined
      }
    />
  )
}
