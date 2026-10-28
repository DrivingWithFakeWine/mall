import { getCategoryTree, type CategoryVO } from '../api/category'
import { useEffect, useState } from 'react'
import { Spin, message } from 'antd'
import CategoryMenu from '../components/CategoryMenu'
import './Home.css'



export default function Home() {
    const [categories, setCategories] = useState<CategoryVO[]>([])
    const [selectedId, setSelectedId] = useState<string>()
    const [loading, setLoading] = useState(true)


    useEffect(() => {
        getCategoryTree()
            .then(setCategories)
            .catch(() => message.error('分类加载失败'))
            .finally(() => setLoading(false))
    }, [])

    const selectedName = categories
        .flatMap(c => [c, ...c.children])
        .find(c => c.id === selectedId)?.name
    return (
        <div className="home-page">
            <header className="home-header">DoroShop</header>
            <div className="home-body">
                <aside className="home-sidebar">
                    {loading ? (
                        <div className="home-sidebar-loading">
                            <Spin />
                        </div>
                    ) : (
                        <CategoryMenu categories={categories} selected={selectedId} onSelect={setSelectedId} />
                    )}
                </aside>
                <main className="home-content">
                    {selectedId ? `当前分类：${selectedName ?? '未知'}` : '请选择左侧分类'}
                </main>
            </div>
        </div>
    )
}