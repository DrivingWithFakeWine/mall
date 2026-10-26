import AuthLayout from '../components/AuthLayout'
import {useState} from 'react'
import {Button,Form,Input,message} from 'antd'
import {loginApi, type LoginParams} from '../api/user.ts'
import {Link, useNavigate} from 'react-router-dom'

export default function Login() {
    const [loading, setLoading] = useState(false)
    const navigate = useNavigate()
    const handleFinsh = async (values: LoginParams) => {
        setLoading(true)
        try {
            const res = await loginApi(values)
            if (res.code === 200) {
                localStorage.setItem('token', res.data.accessToken)
                message.success('登录成功')
                // 跳 首页 ， 还未完成
                navigate('/home')
            } else {
                message.error(res.message)
            }
        } catch {
            message.error('网络异常，稍后重试')
        } finally {
            setLoading(false)
        }
    }

    return (
        <AuthLayout>
            <Form
                name="login"
                layout="vertical"
                onFinish={handleFinsh}
                autoComplete="off"
            >
                <Form.Item
                label="用户名"
                name="username"
                rules={[{ required: true, message: '请输入用户名' }]}
                >
                    <Input placeholder="用户名" />
                </Form.Item>
                <Form.Item
                label="密码"
                name="password"
                rules={[{ required: true, message: '请输入密码' }]}
                >
                    <Input.Password placeholder='密码' />
                </Form.Item>
                <Form.Item>
                    <Button type="primary" htmlType="submit" block loading={loading}>
                        登录
                    </Button>
                    <Link to="/register">去注册</Link>
                </Form.Item>
            </Form>
        </AuthLayout>
    )
}







