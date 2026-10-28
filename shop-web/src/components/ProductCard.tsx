import { Card } from 'antd'
import { Link } from 'react-router-dom'
import type { ProductListItem } from '../api/product'

export default function ProductCard({ product }: { product: ProductListItem }) {
    return (
        <Link to={`/product/${product.id}`}>
            <Card hoverable cover={
                <div className="product-cover">{product.name.slice(0, 1)}</div>
            }>
                <div className="product-name">{product.name}</div>
                <div className="product-subtitle">{product.subtitle}</div>
                <div className="product-price">¥{product.priceMin}</div>
                <div className="product-sales">已售 {product.sales}</div>
            </Card>
        </Link>
    )
}