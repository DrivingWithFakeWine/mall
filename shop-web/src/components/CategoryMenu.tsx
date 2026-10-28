import { Menu } from 'antd'
import type { CategoryVO } from '../api/category'

interface CategoryMenuProps {
    categories: CategoryVO[]
    selected?: string
    onSelect: (categoryId: string) => void
}




export default function CategoryMenu({ categories, selected, onSelect }: CategoryMenuProps) {
    const items = categories.map(c => ({
        key: `${c.id}-group`,              // 父级仅用于展开，不参与选中
        label: c.name,
        children: [
            { key: c.id, label: '全部' },     // 选中它 = 传一级分类 id
            ...c.children.map(child => ({ key: child.id, label: child.name })),
        ],
    }))

    return (
        <Menu
            mode="inline"
            items={items}
            selectedKeys={selected ? [selected] : []}
            onSelect={({ key }) => onSelect(key)}
        />
    )
}