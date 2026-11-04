import { getCategoryTree, type CategoryVO } from '../api/category'
import { useEffect, useState } from 'react'
import { Empty, Pagination, Spin, message } from 'antd'
import CategoryMenu from '../components/CategoryMenu'
import ProductCard from '../components/ProductCard'
import SearchBar from '../components/SearchBar'
import UserNav from '../components/UserNav'
import { Link, useSearchParams } from 'react-router-dom'
import { getProductList, type ProductListItem } from '../api/product'
import './Home.css'

const PAGE_SIZE = 8

export default function Home() {
    const [searchParams, setSearchParams] = useSearchParams()
    const keyword = searchParams.get('keyword')?.trim() || undefined
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
        getProductList({ page, size: PAGE_SIZE, categoryId: selectedId, keyword })
            .then(data => {
                setProducts(data.records)
                setTotal(data.total)
            })
            .catch(() => message.error('商品加载失败'))
            .finally(() => setListLoading(false))
    }, [selectedId, page, keyword])

    // 切换分类时页码必须归 1，否则可能请求到不存在的页
    const handleSelectCategory = (id: string) => {
        setSelectedId(id)
        setPage(1)
    }

    const handleSearch = (kw: string) => {
        const next = new URLSearchParams(searchParams)
        if (kw) {
            next.set('keyword', kw)
        } else {
            next.delete('keyword')
        }
        setSearchParams(next)
        setPage(1)
    }

    return (
        <div className="home-page">
            <header className="home-header">
                <span>DoroShop</span>
                <Link to="/seckill" className="seckill-entry">⚡ 秒杀</Link>
                <div className="home-search">
                    <SearchBar initialValue={keyword ?? ''} onSearch={handleSearch} placeholder="搜索商品" />
                </div>
                <div className="home-nav">
                    <Link to="/orders">我的订单</Link>
                    <Link to="/cart">购物车</Link>
                    <UserNav />
                </div>
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
                            <Empty description={keyword ? `没有找到与“${keyword}”相关的商品` : '该分类下暂无商品'} />
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
