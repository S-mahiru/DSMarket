import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import CategoryChips from './CategoryChips'
import type { CategoryNode } from '@/types/api'

// REQ-20260913 §14 T24。

const CATS: CategoryNode[] = [
  {
    id: 1,
    name: '手机数码',
    level: 1,
    productCount: 3,
    children: [
      { id: 10, name: '智能手机', level: 2, productCount: 3 },
      { id: 11, name: '平板电脑', level: 2, productCount: 0 }
    ]
  },
  { id: 2, name: '电脑办公', level: 1, productCount: 0 }
]

interface ChipView {
  name: string
  count: string | null
  active: boolean
}

function chipsOf(container: HTMLElement): ChipView[][] {
  return [...container.querySelectorAll('.chips-row')].map((row) =>
    [...row.querySelectorAll('.category-chip')].map((c) => ({
      name: c.querySelector('.chip-name')?.textContent ?? '',
      count: c.querySelector('.chip-count')?.textContent ?? null,
      active: c.classList.contains('active')
    }))
  )
}

function nameOf(c: ChipView) {
  return c.name
}

describe('CategoryChips 数量显示', () => {
  it('画出一级分类的名字与数量', () => {
    const { container } = render(<CategoryChips categories={CATS} onSelect={() => {}} />)
    expect(chipsOf(container)[0]).toEqual([
      { name: '手机数码', count: '3', active: false },
      { name: '电脑办公', count: '0', active: false }
    ])
  })

  it('productCount 缺失（E6）→ 退化显示名字、不显示数量', () => {
    const noCount: CategoryNode[] = [
      { id: 1, name: '手机数码', level: 1 },
      { id: 2, name: '电脑办公', level: 1 }
    ]
    const { container } = render(<CategoryChips categories={noCount} onSelect={() => {}} />)
    expect(chipsOf(container)[0]).toEqual([
      { name: '手机数码', count: null, active: false },
      { name: '电脑办公', count: null, active: false }
    ])
  })

  it('productCount = 0 照常显示 0（REQ §12.2 A2：不隐藏分类）', () => {
    const { container } = render(<CategoryChips categories={CATS} onSelect={() => {}} />)
    expect(chipsOf(container)[0].find((c) => c.name === '电脑办公')?.count).toBe('0')
  })
})

describe('CategoryChips「全部」胶囊', () => {
  it('传了 totalCount → 渲染「全部」并带数量', () => {
    const { container } = render(
      <CategoryChips categories={CATS} totalCount={6} onSelect={() => {}} />
    )
    expect(chipsOf(container)[0][0]).toEqual({ name: '全部', count: '6', active: true })
  })

  it('没传 totalCount → 不渲染「全部」（列表页拿不到全站总数，宁可没有也不能显示错数）', () => {
    const { container } = render(<CategoryChips categories={CATS} onSelect={() => {}} />)
    expect(chipsOf(container)[0].map(nameOf)).not.toContain('全部')
  })
})

describe('CategoryChips 选择与高亮', () => {
  it('点一级胶囊 → 回传该 id', () => {
    const onSelect = vi.fn()
    const { container } = render(<CategoryChips categories={CATS} onSelect={onSelect} />)
    fireEvent.click(container.querySelectorAll('.category-chip')[1])
    expect(onSelect).toHaveBeenCalledWith(2)
  })

  it('点「全部」→ 回传 undefined（清除筛选）', () => {
    const onSelect = vi.fn()
    const { container } = render(
      <CategoryChips categories={CATS} totalCount={6} onSelect={onSelect} />
    )
    fireEvent.click(container.querySelectorAll('.category-chip')[0])
    expect(onSelect).toHaveBeenCalledWith(undefined)
  })

  it('选中的是一级 → 该一级高亮', () => {
    const { container } = render(
      <CategoryChips categories={CATS} selectedId={2} onSelect={() => {}} />
    )
    expect(chipsOf(container)[0][1].active).toBe(true)
    expect(chipsOf(container)[0][0].active).toBe(false)
  })

  it('选中的是深层节点 → 高亮回溯到它所属的一级（不是只亮二级）', () => {
    const { container } = render(
      <CategoryChips categories={CATS} selectedId={10} onSelect={() => {}} />
    )
    expect(chipsOf(container)[0][0]).toEqual({ name: '手机数码', count: '3', active: true })
  })
})

describe('CategoryChips 二级行', () => {
  it('选中带子节点的一级 → 出现二级行', () => {
    const { container } = render(
      <CategoryChips categories={CATS} selectedId={1} onSelect={() => {}} />
    )
    expect(chipsOf(container)[1]).toEqual([
      { name: '智能手机', count: '3', active: false },
      { name: '平板电脑', count: '0', active: false }
    ])
  })

  it('二级行里通往选中节点的那个子节点高亮（二级行是面包屑，不是无限下钻）', () => {
    const { container } = render(
      <CategoryChips categories={CATS} selectedId={10} onSelect={() => {}} />
    )
    expect(chipsOf(container)[1][0]).toEqual({ name: '智能手机', count: '3', active: true })
    expect(chipsOf(container)[1][1].active).toBe(false)
  })

  it('选中的一级没有子节点 → 不出现二级行', () => {
    const { container } = render(
      <CategoryChips categories={CATS} selectedId={2} onSelect={() => {}} />
    )
    expect(container.querySelectorAll('.chips-row')).toHaveLength(1)
  })

  it('没选中任何分类 → 不出现二级行', () => {
    const { container } = render(<CategoryChips categories={CATS} onSelect={() => {}} />)
    expect(container.querySelectorAll('.chips-row')).toHaveLength(1)
  })
})

describe('CategoryChips 是受控组件', () => {
  it('组件自己不导航：点击不改变 URL（否则会冲掉 keyword/sortBy/page）', () => {
    const before = window.location.href
    const { container } = render(<CategoryChips categories={CATS} onSelect={() => {}} />)
    fireEvent.click(container.querySelectorAll('.category-chip')[0])
    expect(window.location.href).toBe(before)
  })
})
