import request from './request'
import { unwrap, type ApiResult } from './result'
import type { components } from './schema'

export type UserAddressVO = components['schemas']['UserAddressVO']
export type AddressSaveDTO = components['schemas']['AddressSaveDTO']

export async function getAddressList(): Promise<UserAddressVO[]> {
    const res = await request.get<ApiResult<UserAddressVO[]>>('/api/address/list')
    return unwrap(res)
}

export async function addAddress(dto: AddressSaveDTO): Promise<void> {
    const res = await request.post<ApiResult<null>>('/api/address/add', dto)
    unwrap(res)
}

export async function updateAddress(id: string, dto: AddressSaveDTO): Promise<void> {
    const res = await request.put<ApiResult<null>>(`/api/address/${id}`, dto)
    unwrap(res)
}

export async function removeAddress(id: string): Promise<void> {
    const res = await request.delete<ApiResult<null>>(`/api/address/${id}`)
    unwrap(res)
}
