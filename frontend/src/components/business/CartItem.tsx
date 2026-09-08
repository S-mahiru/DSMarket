import { Button, Checkbox, InputNumber, Popconfirm, Tag } from 'antd'
import { DeleteOutlined } from '@ant-design/icons'
import type { CartItem as CartItemType } from '@/types/cart'
import './CartItem.scss'

interface Props {
  item: CartItemType
  onCheck: (id: number, checked: boolean) => void
  onQuantity: (id: number, quantity: number) => void
  onRemove: (id: number) => void
}

export default function CartItem({ item, onCheck, onQuantity, onRemove }: Props) {
  return (
    <div className="cart-item">
      <Checkbox
        checked={item.checked === 1}
        onChange={(e) => onCheck(item.id, e.target.checked)}
        className="cart-check"
      />
      <img src={item.productImage || '/favicon.svg'} alt="" className="cart-img" />
      <div className="cart-info">
        <div className="cart-name">{item.productName}</div>
        {item.skuSpecs.length > 0 && (
          <div className="cart-specs">
            {item.skuSpecs.map((s) => (
              <Tag key={`${s.key}-${s.value}`}>{s.key}: {s.value}</Tag>
            ))}
          </div>
        )}
      </div>
      <div className="cart-price">¥{item.unitPrice.toFixed(2)}</div>
      <InputNumber
        min={1}
        max={Math.max(item.stock, 1)}
        value={item.quantity}
        onChange={(v) => onQuantity(item.id, v || 1)}
        className="cart-qty"
      />
      <div className="cart-subtotal">¥{item.subtotal.toFixed(2)}</div>
      <Popconfirm title="确认删除该商品？" onConfirm={() => onRemove(item.id)}>
        <Button type="text" danger icon={<DeleteOutlined />} className="cart-delete" />
      </Popconfirm>
    </div>
  )
}
