import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Form, Input, Spin, message } from 'antd'
import { changePassword, getMe, updateProfile, type UserVO } from '../api/user'
import { confirmFieldRule } from '../utils/formRules'
import UserNav from '../components/UserNav'
import './Account.css'

export default function AccountPage() {
    const navigate = useNavigate()
    const [user, setUser] = useState<UserVO>()
    const [loading, setLoading] = useState(true)
    const [savingProfile, setSavingProfile] = useState(false)
    const [savingPassword, setSavingPassword] = useState(false)
    const [profileForm] = Form.useForm()
    const [passwordForm] = Form.useForm()

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        getMe()
            .then(data => {
                setUser(data)
                profileForm.setFieldsValue({
                    nickname: data.nickname,
                    telephone: data.telephone ?? '',
                })
            })
            .catch((e: Error) => message.error(e.message || '账户信息加载失败'))
            .finally(() => setLoading(false))
    }, [navigate, profileForm])

    const handleProfile = async (values: { nickname: string; telephone?: string }) => {
        setSavingProfile(true)
        try {
            const data = await updateProfile({
                nickname: values.nickname,
                telephone: values.telephone?.trim() || null,
            })
            setUser(data)
            window.dispatchEvent(new Event('user-profile-updated'))
            message.success('资料已更新')
        } catch (e) {
            message.error((e as Error).message || '资料更新失败')
        } finally {
            setSavingProfile(false)
        }
    }

    const handlePassword = async (values: {
        oldPassword: string
        newPassword: string
        confirmPassword: string
    }) => {
        setSavingPassword(true)
        try {
            await changePassword({
                oldPassword: values.oldPassword,
                newPassword: values.newPassword,
            })
            passwordForm.resetFields()
            message.success('密码已修改')
        } catch (e) {
            message.error((e as Error).message || '密码修改失败')
        } finally {
            setSavingPassword(false)
        }
    }

    if (loading) {
        return <div className="account-loading"><Spin size="large" /></div>
    }

    return (
        <div className="account-page">
            <header className="account-header">
                <Link to="/home" className="account-logo">DoroShop</Link>
                <span className="account-title">账户中心</span>
                <div className="account-nav">
                    <Link to="/orders">我的订单</Link>
                    <Link to="/cart">购物车</Link>
                    <UserNav />
                </div>
            </header>

            <main className="account-main">
                <section className="account-card">
                    <div className="account-card-title">基本资料</div>
                    <div className="account-username">用户名：{user?.username}</div>
                    <Form form={profileForm} layout="vertical" onFinish={handleProfile}>
                        <Form.Item
                            name="nickname"
                            label="昵称"
                            rules={[
                                { required: true, message: '请输入昵称' },
                                { min: 2, max: 12, message: '昵称为2-12位' },
                            ]}
                        >
                            <Input placeholder="昵称" />
                        </Form.Item>
                        <Form.Item
                            name="telephone"
                            label="手机号"
                            rules={[{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]}
                        >
                            <Input placeholder="手机号，可留空" />
                        </Form.Item>
                        <Button type="primary" htmlType="submit" loading={savingProfile}>
                            保存资料
                        </Button>
                    </Form>
                </section>

                <section className="account-card">
                    <div className="account-card-title">修改密码</div>
                    <Form form={passwordForm} layout="vertical" onFinish={handlePassword} autoComplete="off">
                        <Form.Item
                            name="oldPassword"
                            label="旧密码"
                            rules={[{ required: true, message: '请输入旧密码' }]}
                        >
                            <Input.Password placeholder="旧密码" />
                        </Form.Item>
                        <Form.Item
                            name="newPassword"
                            label="新密码"
                            rules={[
                                { required: true, message: '请输入新密码' },
                                { min: 6, max: 20, message: '密码为6-20位' },
                            ]}
                        >
                            <Input.Password placeholder="6-20 位" />
                        </Form.Item>
                        <Form.Item
                            name="confirmPassword"
                            label="确认新密码"
                            dependencies={['newPassword']}
                            rules={[
                                { required: true, message: '请再次输入新密码' },
                                confirmFieldRule('newPassword', '两次密码不一致'),
                            ]}
                        >
                            <Input.Password placeholder="再次输入新密码" />
                        </Form.Item>
                        <Button type="primary" htmlType="submit" loading={savingPassword}>
                            修改密码
                        </Button>
                    </Form>
                </section>

                <section className="account-card">
                    <div className="account-card-title">快捷入口</div>
                    <div className="account-links">
                        <Link to="/address">收货地址管理</Link>
                        <Link to="/orders">我的订单</Link>
                        <Link to="/cart">购物车</Link>
                    </div>
                </section>
            </main>
        </div>
    )
}
