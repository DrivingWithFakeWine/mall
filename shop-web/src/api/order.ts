import request from './request'
import { unwrap, type ApiResult } from './result'
import type { components } from './schema'

export type OrderConfirmVO = components['schemas']['OrderConfirmVO']
export type OrderListItemVO = components['schemas']['OrderListItemVO']
export type OrderPage = components['schemas']['OrderPage']
export type OrderDetailVO = components['schemas']['OrderDetailVO']
export type OrderPayVO = components['schemas']['OrderPayVO']
export type OrderSubmitVO = components['schemas']['OrderSubmitVO']

export async function confirmOrder(): Promise<OrderConfirmVO> {
    const res = await request.get<ApiResult<OrderConfirmVO>>('/api/order/confirm')
    return unwrap(res)
}

export async function submitOrder(addressId: string, remark?: string): Promise<OrderSubmitVO> {
    const res = await request.post<ApiResult<OrderSubmitVO>>('/api/order/submit', {
        addressId,
        remark,
    })
    return unwrap(res)
}

export async function getOrderList(params: {
    page: number
    size: number
    status?: number
}): Promise<OrderPage> {
    const res = await request.get<ApiResult<OrderPage>>('/api/order/list', { params })
    return unwrap(res)
}

export async function getOrderDetail(id: string): Promise<OrderDetailVO> {
    const res = await request.get<ApiResult<OrderDetailVO>>(`/api/order/${id}`)
    return unwrap(res)
}

export async function payOrder(id: string): Promise<OrderPayVO> {
    const res = await request.post<ApiResult<OrderPayVO>>(`/api/order/${id}/pay`)
    return unwrap(res)
}

export async function cancelOrder(id: string): Promise<void> {
    const res = await request.post<ApiResult<null>>(`/api/order/${id}/cancel`)
    unwrap(res)
}
