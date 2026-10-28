import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, Empty, InputNumber, Spin, Tag, message } from 'antd'
import { getProductDetail, type ProductDetail } from '../api/product'
import { addToCart } from '../api/cart'
import ImageBox from '../components/ImageBox'
import './ProductDetail.css'

export default function ProductDetailPage() {
    const { id } = useParams<{ id: string }>()
    const navigate = useNavigate()
    const [detail, setDetail] = useState<ProductDetail>()
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string>()
    const [selected, setSelected] = useState<Record<string, string>>({})
    const [activeImage, setActiveImage] = useState(0)
    const [quantity, setQuantity] = useState(1)
    const [adding, setAdding] = useState(false)

    useEffect(() => {
        if (!id) return
        setLoading(true)
        setError(undefined)
        getProductDetail(id)
            .then(setDetail)
            .catch((e: Error) => setError(e.message || '商品加载失败'))
            .finally(() => setLoading(false))
    }, [id])

    // 首次加载后默认选中第一个有库存的 SKU 的规格组合
    useEffect(() => {
        if (!detail) return
        const firstAvailable = detail.skus.find(sku => sku.availableStock > 0) ?? detail.skus[0]
        if (!firstAvailable) return

        const parts = firstAvailable.specDesc.split('/')
        const next: Record<string, string> = {}
        detail.specs.forEach((spec, index) => {
            const matched = spec.values.find(value => value.value === parts[index])
            if (matched) next[spec.id] = matched.value
        })
        setSelected(next)
    }, [detail])

    const currentSku = useMemo(() => {
        if (!detail) return undefined
        if (detail.specs.length === 0) return detail.skus[0]
        const allSelected = detail.specs.every(spec => selected[spec.id])
        if (!allSelected) return undefined
        const desc = detail.specs.map(spec => selected[spec.id]).join('/')
        return detail.skus.find(sku => sku.specDesc === desc)
    }, [detail, selected])

    // SKU 专属图排在前面，SPU 通用图（轮播）在后
    const images = useMemo(() => {
        if (!detail) return []
        const skuImages = currentSku
            ? detail.images.filter(image => image.skuId === currentSku.id)
            : []
        const spuImages = detail.images.filter(image => !image.skuId)
        return [...skuImages, ...spuImages]
    }, [detail, currentSku])

    useEffect(() => {
        setActiveImage(0)
    }, [images])

    // 切换规格后数量回到 1
    useEffect(() => {
        setQuantity(1)
    }, [currentSku?.id])

    const handleAddToCart = async () => {
        if (!currentSku) return
        if (quantity > currentSku.availableStock) {
            message.warning(`库存不足，当前仅剩 ${currentSku.availableStock} 件`)
            return
        }
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        setAdding(true)
        try {
            await addToCart(currentSku.id, quantity)
            message.success('已加入购物车')
        } catch (e) {
            message.error((e as Error).message || '加入购物车失败')
        } finally {
            setAdding(false)
        }
    }

    if (loading) {
        return <div className="detail-loading"><Spin size="large" /></div>
    }
    if (error || !detail) {
        return (
            <div className="detail-loading">
                <Empty description={error ?? '商品不存在或已下架'} />
                <Link to="/home">返回首页</Link>
            </div>
        )
    }

    const { spu, specs } = detail
    const price = currentSku?.price ?? spu.priceMin
    const originalPrice = currentSku?.originalPrice
    const soldOut = !!currentSku && currentSku.availableStock <= 0
    const mainImage = images[activeImage] ?? images[0]

    return (
        <div className="detail-page">
            <header className="detail-header">
                <Link to="/home">← 返回</Link>
                <span className="detail-brand">DoroShop</span>
                <Link to="/cart" style={{ marginLeft: 'auto', fontSize: 14 }}>购物车</Link>
            </header>

            <div className="detail-body">
                <div className="detail-gallery">
                    <ImageBox
                        src={mainImage?.url}
                        label={spu.name}
                        className="detail-main-image"
                    />
                    <div className="detail-thumbs">
                        {images.map((image, index) => (
                            <button
                                key={image.id}
                                type="button"
                                className={`detail-thumb ${index === activeImage ? 'active' : ''}`}
                                onClick={() => setActiveImage(index)}
                            >
                                <ImageBox src={image.url} label={spu.name} />
                            </button>
                        ))}
                    </div>
                </div>

                <div className="detail-info">
                    <h1 className="detail-name">{spu.name}</h1>
                    <p className="detail-subtitle">{spu.subtitle}</p>
                    <div className="detail-price">
                        <span className="price-now">¥{price}</span>
                        {originalPrice != null && (
                            <span className="price-original">¥{originalPrice}</span>
                        )}
                        <span className="detail-sales">已售 {spu.sales}</span>
                    </div>

                    {specs.map(spec => (
                        <div className="detail-spec" key={spec.id}>
                            <span className="spec-label">{spec.name}</span>
                            <div className="spec-values">
                                {spec.values.map(value => (
                                    <button
                                        key={value.id}
                                        type="button"
                                        className={`spec-value ${selected[spec.id] === value.value ? 'active' : ''}`}
                                        onClick={() =>
                                            setSelected(prev => ({ ...prev, [spec.id]: value.value }))
                                        }
                                    >
                                        {value.value}
                                    </button>
                                ))}
                            </div>
                        </div>
                    ))}

                    <div className="detail-stock">
                        {currentSku ? (
                            soldOut
                                ? <Tag color="red">已售罄</Tag>
                                : `库存 ${currentSku.availableStock} 件`
                        ) : (
                            '请选择规格'
                        )}
                    </div>

                    <div className="detail-quantity">
                        <span>数量</span>
                        <InputNumber
                            min={1}
                            max={Math.min(99, currentSku?.availableStock ?? 99)}
                            value={quantity}
                            onChange={value => setQuantity(value ?? 1)}
                            disabled={!currentSku || soldOut}
                        />
                    </div>

                    <Button
                        type="primary"
                        size="large"
                        block
                        loading={adding}
                        disabled={!currentSku || soldOut}
                        onClick={handleAddToCart}
                    >
                        {soldOut ? '已售罄' : '加入购物车'}
                    </Button>
                </div>
            </div>

            <section className="detail-rich">
                <h2>商品详情</h2>
                <p>{spu.detail || '暂无详情'}</p>
            </section>
        </div>
    )
}
