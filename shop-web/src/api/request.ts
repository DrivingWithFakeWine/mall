import axios from 'axios'

/** 统一请求实例：自动携带登录 token，统一处理未登录/登录过期 */
const request = axios.create()

request.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    // 过滤历史遗留的 "undefined"/"null" 字符串
    if (token && token !== 'undefined' && token !== 'null') {
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})

request.interceptors.response.use(
    response => response,
    error => {
        if (error.response?.status === 401) {
            localStorage.removeItem('token')
            if (!window.location.pathname.startsWith('/login')) {
                window.location.href = '/login'
            }
            return Promise.reject(new Error('登录已过期，请重新登录'))
        }
        return Promise.reject(error)
    },
)

export default request
