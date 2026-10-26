import axios from 'axios'

export interface LoginParams {
    username: string
    password: string
}

export interface LoginResult {
    code: number
    message: string
    data: {
        accessToken: string
        userId: string
        nickname: string
    }
}

export async function loginApi(params: LoginParams) {
    const res = await axios.post<LoginResult>('/api/user/login', params)
    return res.data
}

export interface RegisterParams {
    username: string
    nickname: string
    password: string
    telephone: string
}

export interface RegisterResult {
    code: number
    message: string
    data: {

    }
}

export async function registerApi(params: RegisterParams) {
    const res = await axios.post<RegisterResult>('/api/user/register', params)
    return res.data
}