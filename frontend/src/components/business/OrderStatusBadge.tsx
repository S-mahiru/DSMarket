import { Tag } from 'antd'

interface Props {
  status: number
  statusName?: string
}

const STATUS_COLOR: Record<number, string> = {
  0: 'orange',
  1: 'blue',
  2: 'cyan',
  3: 'green',
  4: 'green',
  5: 'default',
  6: 'default'
}

const DEFAULT_NAME: Record<number, string> = {
  0: '待付款',
  1: '已付款',
  2: '已发货',
  3: '已收货',
  4: '已完成',
  5: '已取消',
  6: '已关闭'
}

export default function OrderStatusBadge({ status, statusName }: Props) {
  return <Tag color={STATUS_COLOR[status] ?? 'default'}>{statusName || DEFAULT_NAME[status] || status}</Tag>
}
