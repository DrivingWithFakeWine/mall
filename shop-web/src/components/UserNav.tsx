import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { message } from 'antd'
import { getMe, type UserVO } from '../api/user'
import './UserNav.css'

/** 头部登录态：登录后显示昵称与退出，未登录显示登录/注册。 */
export default function UserNav() {
    const navigate = useNavigate()
    const [user, setUser] = useState<UserVO>()

    useEffect(() => {
        const refresh = () => {
            if (!localStorage.getItem('token')) {
                setUser(undefined)
                return
            }
            getMe()
                .then(setUser)
                .catch(() => {
                    // token 失效由 request 拦截器统一处理
                })
        }
        refresh()
        window.addEventListener('user-profile-updated', refresh)
        return () => window.removeEventListener('user-profile-updated', refresh)
    }, [])

    const handleLogout = () => {
        localStorage.removeItem('token')
        setUser(undefined)
        message.success('已退出登录')
        navigate('/home')
    }

    if (!user) {
        return (
            <span className="user-nav">
                <Link to="/login">登录</Link>
                <Link to="/register">注册</Link>
            </span>
        )
    }

    return (
        <span className="user-nav">
            <Link to="/account" className="user-nav-name">{user.nickname}</Link>
            <button type="button" className="user-nav-logout" onClick={handleLogout}>退出</button>
        </span>
    )
}
