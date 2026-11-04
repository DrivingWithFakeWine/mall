import request from './request'
import { unwrap, type ApiResult } from './result'
import type { components } from './schema'

export type LoginParams = components['schemas']['LoginDTO']
export type LoginVO = components['schemas']['LoginVO']
export type RegisterParams = components['schemas']['RegisterDTO']
export type UserVO = components['schemas']['UserVO']
export type ProfileUpdateDTO = components['schemas']['ProfileUpdateDTO']
export type PasswordChangeDTO = components['schemas']['PasswordChangeDTO']

export async function loginApi(params: LoginParams): Promise<LoginVO> {
    const res = await request.post<ApiResult<LoginVO>>('/api/user/login', params)
    return unwrap(res)
}

export async function registerApi(params: RegisterParams): Promise<void> {
    const res = await request.post<ApiResult<null>>('/api/user/register', params)
    unwrap(res)
}

export async function getMe(): Promise<UserVO> {
    const res = await request.get<ApiResult<UserVO>>('/api/user/me')
    return unwrap(res)
}

export async function updateProfile(dto: ProfileUpdateDTO): Promise<UserVO> {
    const res = await request.put<ApiResult<UserVO>>('/api/user/profile', dto)
    return unwrap(res)
}

export async function changePassword(dto: PasswordChangeDTO): Promise<void> {
    const res = await request.put<ApiResult<null>>('/api/user/password', dto)
    unwrap(res)
}
