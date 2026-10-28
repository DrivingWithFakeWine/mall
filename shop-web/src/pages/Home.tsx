import { getCategoryTree, type CategoryVO } from '../api/category'
import { useEffect, useState } from 'react'
import { Empty, Pagination, Spin, message } from 'antd'
import CategoryMenu from '../components/CategoryMenu'
import ProductCard from '../components/ProductCard'
import { Link } from 'react-router-dom'
import { getProductList, type ProductListItem } from '../api/product'
import './Home.css'

const PAGE_SIZE = 8

export default function Home() {
    const [categories, setCategories] = useState<CategoryVO[]>([])
    const [selectedId, setSelectedId] = useState<string>()
    const [loading, setLoading] = useState(true)

    const [products, setProducts] = useState<ProductListItem[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [listLoading, setListLoading] = useState(false)

    useEffect(() => {
        getCategoryTree()
            .then(setCategories)
            .catch(() => message.error('分类加载失败'))
            .finally(() => setLoading(false))
    }, [])

    useEffect(() => {
        setListLoading(true)
        getProductList({ page, size: PAGE_SIZE, categoryId: selectedId })
            .then(data => {
                setProducts(data.records)
                setTotal(data.total)
            })
            .catch(() => message.error('商品加载失败'))
            .finally(() => setListLoading(false))
    }, [selectedId, page])

    // 切换分类时页码必须归 1，否则可能请求到不存在的页
    const handleSelectCategory = (id: string) => {
        setSelectedId(id)
        setPage(1)
    }

    return (
        <div className="home-page">
            <header className="home-header">
                <span>DoroShop</span>
                <Link to="/cart" style={{ marginLeft: 'auto', fontSize: 14 }}>购物车</Link>
            </header>
            <div className="home-body">
                <aside className="home-sidebar">
                    {loading ? (
                        <div className="home-sidebar-loading">
                            <Spin />
                        </div>
                    ) : (
                        <CategoryMenu
                            categories={categories}
                            selected={selectedId}
                            onSelect={handleSelectCategory}
                        />
                    )}
                </aside>
                <main className="home-content">
                    <Spin spinning={listLoading}>
                        {products.length === 0 ? (
                            <Empty description="该分类下暂无商品" />
                        ) : (
                            <div className="product-grid">
                                {products.map(product => (
                                    <ProductCard key={product.id} product={product} />
                                ))}
                            </div>
                        )}
                    </Spin>
                    <Pagination
                        current={page}
                        pageSize={PAGE_SIZE}
                        total={total}
                        onChange={setPage}
                        style={{ textAlign: 'center', marginTop: 16 }}
                    />
                </main>
            </div>
        </div>
    )
}
