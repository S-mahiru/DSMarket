import { useCallback, useRef } from 'react'
import { matchPath, useMatches } from 'react-router-dom'
import type { ChatContextPayload } from '@/types/ai'

type Match = ReturnType<typeof useMatches>[number]

/**
 * 从当前路由派生 `context`（C1 §2）。
 *
 * **必须用 `useMatches()`，不能用 `useParams()`**：本组件挂在 `MainLayout`，而它是一条
 * **无路径的布局路由**（`{ element: <MainLayout/>, children: [...] }`），它的 RouteContext
 * 里**不带 params** —— `useParams()` 在这个位置恒为空对象。用它取 `orderNo`/`productId`，
 * 结果是这两个字段**静默不发**：不报错、不 400，AI 只是答得越来越泛，没有任何地方会报出来。
 */
function derive(matches: Match[]): ChatContextPayload | undefined {
  // 只看**最深的一层**（叶子 = 当前页面组件）。刻意不向浅层回退：布局路由的 pathname 也是 "/"，
  // 一旦回退，`/products`、`/cart` 这类未列入白名单的页面会被全部当成 `page=home`。
  const leaf = matches[matches.length - 1]
  if (!leaf) return undefined
  const { pathname } = leaf

  if (matchPath('/', pathname)) return { page: 'home' }
  if (matchPath('/orders', pathname)) return { page: 'order_list' }

  const orderDetail = matchPath('/order/:orderNo', pathname)
  if (orderDetail) {
    const orderNo = orderDetail.params.orderNo
    // page=order_detail 时 orderNo 必填。拿不到就**不传 context**，而不是传一个残缺的对象
    // —— 服务端对"不属于本人的 orderNo"是忽略语义，但前端没理由主动发一份自己都知道不全的上下文。
    return orderNo ? { page: 'order_detail', orderNo } : undefined
  }

  const productDetail = matchPath('/product/:id', pathname)
  if (productDetail) {
    const id = Number(productDetail.params.id)
    return Number.isInteger(id) && id > 0 ? { page: 'product_detail', productId: id } : undefined
  }

  // 其余页面（商品列表、购物车、结算…）不在白名单里 → 干脆不传。
  // 传白名单外的 page 会在**开流前 400**，用户看到的是"客服坏了"。
  return undefined
}

/**
 * 返回一个**读取函数**，而不是上下文本身 —— 需求要求"只在发送时读取"：
 * 抽屉可以一直开着，用户在这期间从首页逛到订单详情页，发出去的那句话必须带上**当下**的页面。
 */
export function useChatContextReader(): () => ChatContextPayload | undefined {
  const matches = useMatches()
  const latest = useRef(matches)
  // 渲染期同步最新值：发送发生在点击回调里，那时读到的是最近一次渲染的 matches，
  // 正是我们要的（能点按钮就说明页面早已渲染完）。换成 useEffect 会慢一拍。
  latest.current = matches
  return useCallback(() => derive(latest.current), [])
}
