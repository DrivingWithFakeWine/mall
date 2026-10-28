import axios from 'axios'
import type { components } from './schema'

export type ProductListItem = components['schemas']['ProductListItem']
export type ProductPage = components['schemas']['ProductPage']
export type ProductDetail = components['schemas']['ProductDetail']

// 后端未联调时用；与 data.sql 一致
const MOCK_PAGE: ProductPage = {
    total: 2,
    records: [
        { id: '1910000000000000001', name: 'iPhone 17', subtitle: 'A19 芯片 · 全新影像系统',
            mainImage: '/images/spu/iphone17-main.jpg', priceMin: 5999.00, sales: 1200 },
        { id: '1910000000000000002', name: '小米 15', subtitle: '骁龙旗舰 · 徕卡影像',
            mainImage: '/images/spu/xiaomi15-main.jpg', priceMin: 3999.00, sales: 860 },
    ],
}

const USE_MOCK = false    // 后端 /product/list 验证通过后改 false

export async function getProductList(params: { page: number; size: number; categoryId?: string }) {
    if (USE_MOCK) return MOCK_PAGE
    const res = await axios.get<{ code: number; message: string; data: ProductPage }>(
        '/api/product/list', { params })
    return res.data.data
}

export async function getProductDetail(id: string): Promise<ProductDetail> {
    const res = await axios.get<{ code: number; message: string; data: ProductDetail }>(
        `/api/product/${id}`)
    if (res.data.code !== 200) {
        throw new Error(res.data.message)
    }
    return res.data.data
}