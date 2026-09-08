import { useNavigate } from 'react-router-dom'
import type { CategoryNode } from '@/types/api'

interface Props {
  categories: CategoryNode[]
  selectedId?: number
}

export default function CategoryTree({ categories, selectedId }: Props) {
  const navigate = useNavigate()

  function renderNodes(nodes: CategoryNode[], depth: number) {
    return nodes.map((node) => (
      <div key={node.id}>
        <div
          className={`category-item ${node.id === selectedId ? 'active' : ''}`}
          style={{ paddingLeft: 12 + depth * 16 }}
          onClick={() => navigate(`/products?categoryId=${node.id}`)}
        >
          {node.name}
        </div>
        {node.children && node.children.length > 0 && renderNodes(node.children, depth + 1)}
      </div>
    ))
  }

  return <div className="category-tree">{renderNodes(categories, 0)}</div>
}
