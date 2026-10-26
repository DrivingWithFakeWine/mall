import { useState } from 'react'
import { registerApi, type RegisterParams } from '../api/user.ts'
import { Button,Form,Input,message } from 'antd'
import { Link, useNavigate } from "react-router-dom";
import { confirmFieldRule, phoneRule, usernameRule, nicknameRule } from "../utils/formRules.ts";
import AuthLayout from '../components/AuthLayout';



export default function Register() {
    const[loading, setLoading] = useState(false)
    const navigate = useNavigate()
    const handleFinsh = async (values: RegisterParams) => {
        setLoading(true)
        try {
            const res = await registerApi(values)
            if (res.code === 200) {
                // 跳转到登录界面，并自动填写好username 和 password
                navigate('/login')
                message.success('注册成功')
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
                name='register'
                layout='vertical'
                onFinish={handleFinsh}
                autoComplete='off'
            >
                <Form.Item
                    label="用户名"
                    name="username"
                    rules={[{ required: true, message: '用户名不能为空' }, usernameRule]}
                    >
                        <Input placeholder='用户名' />
                </Form.Item>
                <Form.Item
                    label="昵称"
                    name="nickname"
                    rules={[{ required: true, message: '昵称不能为空' }, nicknameRule]}
                >
                    <Input placeholder='请输入昵称,可是4-12为英文、数字、汉字' />
                </Form.Item>
                <Form.Item
                    label="密码"
                    name="password"
                    rules={[{ required: true, message: '请设置密码' }]}
                >
                    <Input.Password placeholder='密码' />
                </Form.Item>
                <Form.Item
                    label="确认密码"
                    name="doublePassword"
                    dependencies={['password']}
                    rules={[
                        { required: true, message: '请输入确认密码' },
                        confirmFieldRule('password', '两次密码不一致'),
                    ]}
                >
                    <Input.Password placeholder='确认密码' />
                </Form.Item>
                <Form.Item
                    label="电话号码"
                    name="telephone"
                    rules={[{ required: false, message: '输入电话号码' }, phoneRule]}
                >
                    <Input placeholder='电话号码，可为空' />
                </Form.Item>
                <Form.Item>
                    <Button type="primary" htmlType="submit" block loading={loading}>
                        注册
                    </Button>
                    <Link to="/login">去登录</Link>
                </Form.Item>
            </Form>
        </AuthLayout>
    )
}

