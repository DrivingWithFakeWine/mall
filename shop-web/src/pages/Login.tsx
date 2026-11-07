import AuthLayout from '../components/AuthLayout'
import {useState} from 'react'
import {Button,Form,Input,Typography,message} from 'antd'
import {loginApi, type LoginParams} from '../api/user.ts'
import {Link, useNavigate} from 'react-router-dom'

// 演示账号：仅用于简历 Demo 展示，正式环境请删除本段并移除 Form 上的 initialValues
const DEMO_ACCOUNT = { username: 'demo', password: 'demo123' }

export default function Login() {
    const [loading, setLoading] = useState(false)
    const navigate = useNavigate()
    const handleFinsh = async (values: LoginParams) => {
        setLoading(true)
        try {
            const data = await loginApi(values)
            localStorage.setItem('token', data.accessToken)
            message.success('登录成功')
            navigate('/home')
        } catch (e) {
            message.error((e as Error).message || '登录失败')
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
                initialValues={DEMO_ACCOUNT}
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
                    <Typography.Text
                        type="secondary"
                        style={{ display: 'block', marginTop: 12, fontSize: 12, lineHeight: '18px' }}
                    >
                        演示账号 {DEMO_ACCOUNT.username} / {DEMO_ACCOUNT.password} 已自动填入，直接点「登录」即可
                    </Typography.Text>
                </Form.Item>
            </Form>
        </AuthLayout>
    )
}







