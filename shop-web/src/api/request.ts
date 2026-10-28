import axios from 'axios'

/** 统一请求实例：自动携带登录 token */
const request = axios.create()

request.interceptors.request.use(config => {
    const token = localStorage.getItem('token')
    if (token) {
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})

export default request
