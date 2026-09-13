/**
 * 前台商品排序选项。列表页与店铺页共用同一份，避免两处各拷一份后分叉 ——
 * 在列表页新增一个排序项而店铺页没有，页面上看不出来（REQ-20260913 §4.3 / §4.6）。
 *
 * <p>value 的 `{sortBy}-{sortOrder}` 形式与 `updateParams` / `handleSortChange` 的
 * 解析约定一致，两边都按第一个 `-` 拆。</p>
 */
export const SORT_OPTIONS = [
  { value: 'sales-desc', label: '销量优先' },
  { value: 'price-asc', label: '价格从低到高' },
  { value: 'price-desc', label: '价格从高到低' },
  { value: 'createdAt-desc', label: '最新上架' }
]

export const DEFAULT_SORT = 'sales-desc'

/** `'price-asc'` → `{ sortBy: 'price', sortOrder: 'asc' }` */
export function parseSort(value: string): { sortBy: string; sortOrder: string } {
  const [sortBy, sortOrder] = value.split('-')
  return { sortBy, sortOrder }
}
