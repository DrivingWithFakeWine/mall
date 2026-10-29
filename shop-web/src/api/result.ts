/** 后端统一响应结构：HTTP 恒为 200，业务结果在 code 里 */
export interface ApiResult<T> {
    code: number
    message: string
    data: T
}

/** 校验业务码并取出 data；非 200 抛出带后端消息的错误 */
export function unwrap<T>(res: { data: ApiResult<T> }): T {
    if (res.data.code !== 200) {
        throw new Error(res.data.message || '请求失败')
    }
    return res.data.data
}
