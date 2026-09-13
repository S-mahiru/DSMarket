import type { CategoryNode } from '@/types/api'
import './CategoryChips.scss'

interface Props {
  categories: CategoryNode[]
  /** 当前选中的分类 id。可以是任意层级的节点 —— 组件会回溯到它所属的一级分类再决定高亮 */
  selectedId?: number
  /**
   * 全站商品数。**只有传了才渲染「全部」胶囊**。
   *
   * 首页拿得到（根节点计数之和，等于不带筛选的列表 `total`）；
   * 列表页拿不到 —— 那里的 `total` 是**筛选后**的条数，拿它当「全部」会显示错数，
   * 所以列表页不传，改用工具栏的「清除筛选」回到全部。
   */
  totalCount?: number
  onSelect: (id?: number) => void
}

/** 节点自身或任一后代是 `id` —— 把深层选中回溯到它所属的一级分类 */
function containsId(node: CategoryNode, id: number): boolean {
  return node.id === id || (node.children ?? []).some((child) => containsId(child, id))
}

function Chip({
  name,
  count,
  active,
  onClick
}: {
  name: string
  count?: number | null
  active: boolean
  onClick: () => void
}) {
  return (
    <button type="button" className={`category-chip${active ? ' active' : ''}`} onClick={onClick}>
      <span className="chip-name">{name}</span>
      {/* E6：字段缺失时退化显示名字，而不是显示「全部 0」 */}
      {count != null && <span className="chip-count">{count}</span>}
    </button>
  )
}

/**
 * 顶部分类胶囊（REQ-20260913 §4.1）。
 *
 * <p>与它取代的 `CategoryTree` 相反，本组件是**受控组件、内部不导航** ——
 * 导航留在调用方。旧树组件内部直接 `navigate('/products?categoryId=N')` 会把
 * `keyword`/`sortBy`/`page` 一并冲掉，这正是本 REQ 要修的两个既有缺陷的成因。</p>
 */
export default function CategoryChips({ categories, selectedId, totalCount, onSelect }: Props) {
  const activeRoot =
    selectedId == null ? undefined : categories.find((c) => containsId(c, selectedId))

  // 二级行是「当前一级分类的子节点」。selection 更深时（如 苹果 → 智能手机 → …），
  // 高亮落在**通往选中节点的那个子节点**上，而不是相对选中节点再展开一层 ——
  // 二级行的语义是面包屑，不是无限下钻。
  const subCategories = activeRoot?.children ?? []
  const activeSub =
    selectedId == null ? undefined : subCategories.find((c) => containsId(c, selectedId))

  return (
    <div className="category-chips">
      <div className="chips-row">
        {totalCount != null && (
          <Chip name="全部" count={totalCount} active={selectedId == null} onClick={() => onSelect(undefined)} />
        )}
        {categories.map((c) => (
          <Chip
            key={c.id}
            name={c.name}
            count={c.productCount}
            active={activeRoot?.id === c.id}
            onClick={() => onSelect(c.id)}
          />
        ))}
      </div>

      {subCategories.length > 0 && (
        <div className="chips-row chips-row-sub">
          {subCategories.map((c) => (
            <Chip
              key={c.id}
              name={c.name}
              count={c.productCount}
              active={activeSub?.id === c.id}
              onClick={() => onSelect(c.id)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
