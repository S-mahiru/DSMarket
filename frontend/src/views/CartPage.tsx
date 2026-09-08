import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Checkbox, Empty, Popconfirm, message } from 'antd'
import { getCart, checkAllCart, clearCartChecked, removeCartItem, updateCartChecked, updateCartQuantity } from '@/api/cart'
import { useCartStore } from '@/stores/cart'
import type { CartItem as CartItemType } from '@/types/cart'
import CartItem from '@/components/business/CartItem'
import PageError from '@/components/business/PageError'
import './CartPage.scss'

export default function CartPage() {
  const navigate = useNavigate()
  const fetchCount = useCartStore((s) => s.fetchCount)
  const [items, setItems] = useState<CartItemType[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError(false)
    getCart()
      .then(setItems)
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const checkedItems = useMemo(() => items.filter((i) => i.checked === 1), [items])
  const allChecked = items.length > 0 && checkedItems.length === items.length
  const someChecked = checkedItems.length > 0 && !allChecked
  const totalAmount = useMemo(() => checkedItems.reduce((sum, i) => sum + i.subtotal, 0), [checkedItems])
  const totalCount = useMemo(() => checkedItems.reduce((sum, i) => sum + i.quantity, 0), [checkedItems])

  function refreshCount() {
    fetchCount()
  }

  function handleCheck(id: number, checked: boolean) {
    updateCartChecked(id, checked ? 1 : 0).then(() => load())
  }

  function handleQuantity(id: number, quantity: number) {
    updateCartQuantity(id, quantity).then(() => load())
  }

  function handleRemove(id: number) {
    removeCartItem(id).then(() => {
      load()
      refreshCount()
    })
  }

  function handleCheckAll(checked: boolean) {
    checkAllCart(checked ? 1 : 0).then(() => load())
  }

  function handleClearChecked() {
    clearCartChecked().then(() => {
      message.success('已清空已选商品')
      load()
      refreshCount()
    })
  }

  if (error) {
    return <PageError onRetry={load} />
  }

  if (loading) {
    return <div className="page-loading">加载中...</div>
  }

  if (items.length === 0) {
    return (
      <div className="cart-empty">
        <Empty description="购物车是空的">
          <Button type="primary" onClick={() => navigate('/products')}>
            去逛逛
          </Button>
        </Empty>
      </div>
    )
  }

  return (
    <div className="cart-page">
      <h2 className="cart-title">购物车</h2>
      <div className="cart-list">
        <div className="cart-header">
          <Checkbox checked={allChecked} indeterminate={someChecked} onChange={(e) => handleCheckAll(e.target.checked)} className="cart-check">
            全选
          </Checkbox>
          <span className="cart-header-col">单价</span>
          <span className="cart-header-col">数量</span>
          <span className="cart-header-col">小计</span>
          <span className="cart-header-col">操作</span>
        </div>
        {items.map((item) => (
          <CartItem key={item.id} item={item} onCheck={handleCheck} onQuantity={handleQuantity} onRemove={handleRemove} />
        ))}
      </div>

      <div className="cart-footer">
        <div className="cart-footer-left">
          <Popconfirm title="确认清空已选商品？" onConfirm={handleClearChecked} disabled={checkedItems.length === 0}>
            <Button type="link" disabled={checkedItems.length === 0}>
              清空已选
            </Button>
          </Popconfirm>
          <span>
            已选 <b>{totalCount}</b> 件，合计 <b className="cart-total">¥{totalAmount.toFixed(2)}</b>
          </span>
        </div>
        <Button type="primary" size="large" disabled={checkedItems.length === 0} onClick={() => navigate('/checkout')}>
          去结算
        </Button>
      </div>
    </div>
  )
}
