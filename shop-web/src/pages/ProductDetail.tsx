import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button, Empty, Spin, Tag, message } from 'antd'
import { getProductDetail, type ProductDetail } from '../api/product'
import './ProductDetail.css'

interface ImageBoxProps {
    src?: string | null
    label: string
    className?: string
}

/** 图片不存在或加载失败时显示渐变占位块，避免裂图 */
function ImageBox({ src, label, className }: ImageBoxProps) {
    const [failed, setFailed] = useState(false)

    useEffect(() => {
        setFailed(false)
    }, [src])

    if (!src || failed) {
        return <div className={`image-placeholder ${className ?? ''}`}>{label.slice(0, 1)}</div>
    }
    return (
        <img
            className={className}
            src={src}
            alt={label}
            onError={() => setFailed(true)}
        />
    )
}

export default function ProductDetailPage() {
    const { id } = useParams<{ id: string }>()
    const [detail, setDetail] = useState<ProductDetail>()
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string>()
    const [selected, setSelected] = useState<Record<string, string>>({})
    const [activeImage, setActiveImage] = useState(0)

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

                    <Button
                        type="primary"
                        size="large"
                        block
                        disabled={!currentSku || soldOut}
                        onClick={() => message.info('购物车功能开发中')}
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
