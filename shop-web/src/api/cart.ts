import request from './request'
import type { components } from './schema'

export type CartItemVO = components['schemas']['CartItemVO']
export type CartListVO = components['schemas']['CartListVO']

/** 后端统一样式：HTTP 恒为 200，业务结果在 code 里 */
interface ApiResult<T> {
    code: number
    message: string
    data: T
}

function unwrap<T>(res: { data: ApiResult<T> }): T {
    if (res.data.code !== 200) {
        throw new Error(res.data.message || '请求失败')
    }
    return res.data.data
}

export async function addToCart(skuId: string, quantity: number): Promise<void> {
    const res = await request.post<ApiResult<null>>('/api/cart/add', { skuId, quantity })
    unwrap(res)
}

export async function getCartList(): Promise<CartListVO> {
    const res = await request.get<ApiResult<CartListVO>>('/api/cart/list')
    return unwrap(res)
}

export async function updateCartQuantity(id: string, quantity: number): Promise<void> {
    const res = await request.put<ApiResult<null>>('/api/cart/quantity', { id, quantity })
    unwrap(res)
}

export async function updateCartSelected(id: string, selected: number): Promise<void> {
    const res = await request.put<ApiResult<null>>('/api/cart/select', { id, selected })
    unwrap(res)
}

export async function removeCartItem(id: string): Promise<void> {
    const res = await request.delete<ApiResult<null>>(`/api/cart/${id}`)
    unwrap(res)
}
