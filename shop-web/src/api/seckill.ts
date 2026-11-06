import request from './request'
import { unwrap, type ApiResult } from './result'
import type { components } from './schema'

export type SeckillActivity = components['schemas']['SeckillActivityListItem']
export type SeckillActivityDetail = components['schemas']['SeckillActivityDetail']
export type SeckillPage = components['schemas']['SeckillPage']
export type SeckillOrderDTO = components['schemas']['SeckillOrderDTO']
export type SeckillOrderSubmitVO = components['schemas']['SeckillOrderSubmitVO']

export async function getSeckillList(params: {
    page: number
    size: number
    keyword?: string
}): Promise<SeckillPage> {
    const res = await request.get<ApiResult<SeckillPage>>('/api/seckill/list', { params })
    return unwrap(res)
}

export async function getSeckillDetail(id: string): Promise<SeckillActivityDetail> {
    const res = await request.get<ApiResult<SeckillActivityDetail>>(`/api/seckill/${id}`)
    return unwrap(res)
}

export async function submitSeckillOrder(id: string, dto: SeckillOrderDTO): Promise<SeckillOrderSubmitVO> {
    const res = await request.post<ApiResult<SeckillOrderSubmitVO>>(`/api/seckill/${id}/order`, dto)
    return unwrap(res)
}

export async function getSeckillOrderResult(token: string): Promise<SeckillOrderSubmitVO> {
    const res = await request.get<ApiResult<SeckillOrderSubmitVO>>('/api/seckill/order/result', {
        params: { token },
    })
    return unwrap(res)
}
