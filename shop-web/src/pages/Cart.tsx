import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Checkbox, Empty, InputNumber, Popconfirm, Spin, Tag, message } from 'antd'
import {
    getCartList,
    removeCartItem,
    updateCartQuantity,
    updateCartSelected,
    type CartItemVO,
} from '../api/cart'
import ImageBox from '../components/ImageBox'
import './Cart.css'

export default function CartPage() {
    const navigate = useNavigate()
    const [items, setItems] = useState<CartItemVO[]>([])
    const [loading, setLoading] = useState(true)
    const [busyId, setBusyId] = useState<string>()

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        getCartList()
            .then(data => setItems(data.items))
            .catch((e: Error) => message.error(e.message || '购物车加载失败'))
            .finally(() => setLoading(false))
    }, [navigate])

    const total = useMemo(
        () => items
            .filter(item => item.selected === 1 && !item.invalid)
            .reduce((sum, item) => sum + Number(item.price) * item.quantity, 0),
        [items],
    )

    const handleQuantity = async (item: CartItemVO, quantity: number) => {
        setBusyId(item.id)
        try {
            await updateCartQuantity(item.id, quantity)
            setItems(prev => prev.map(x => (x.id === item.id ? { ...x, quantity } : x)))
        } catch (e) {
            message.error((e as Error).message || '修改数量失败')
        } finally {
            setBusyId(undefined)
        }
    }

    const handleSelect = async (item: CartItemVO, checked: boolean) => {
        const selected = checked ? 1 : 0
        setBusyId(item.id)
        try {
            await updateCartSelected(item.id, selected)
            setItems(prev => prev.map(x => (x.id === item.id ? { ...x, selected } : x)))
        } catch (e) {
            message.error((e as Error).message || '修改勾选失败')
        } finally {
            setBusyId(undefined)
        }
    }

    const handleRemove = async (item: CartItemVO) => {
        try {
            await removeCartItem(item.id)
            setItems(prev => prev.filter(x => x.id !== item.id))
            message.success('已删除')
        } catch (e) {
            message.error((e as Error).message || '删除失败')
        }
    }

    return (
        <div className="cart-page">
            <header className="cart-header">
                <Link to="/home">← 返回首页</Link>
                <span className="cart-title">购物车</span>
            </header>

            <div className="cart-body">
                <Spin spinning={loading}>
                    {items.length === 0 ? (
                        <Empty description="购物车还是空的">
                            <Link to="/home">去逛逛</Link>
                        </Empty>
                    ) : (
                        <div className="cart-list">
                            {items.map(item => (
                                <div className={`cart-item ${item.invalid ? 'invalid' : ''}`} key={item.id}>
                                    <Checkbox
                                        checked={item.selected === 1}
                                        disabled={item.invalid}
                                        onChange={e => handleSelect(item, e.target.checked)}
                                    />
                                    <div className="cart-thumb">
                                        <ImageBox src={item.image} label={item.name} />
                                    </div>
                                    <div className="cart-info">
                                        <div className="cart-name">{item.name}</div>
                                        <div className="cart-spec">{item.specDesc}</div>
                                        {item.invalid && <Tag color="red">已失效</Tag>}
                                    </div>
                                    <div className="cart-price">¥{Number(item.price).toFixed(2)}</div>
                                    <InputNumber
                                        min={1}
                                        max={Math.min(99, Math.max(item.availableStock, 1))}
                                        value={item.quantity}
                                        disabled={item.invalid || busyId === item.id}
                                        onChange={value => {
                                            if (value && value !== item.quantity) {
                                                handleQuantity(item, value)
                                            }
                                        }}
                                    />
                                    <div className="cart-subtotal">
                                        ¥{(Number(item.price) * item.quantity).toFixed(2)}
                                    </div>
                                    <Popconfirm title="确定删除该商品吗？" onConfirm={() => handleRemove(item)}>
                                        <Button type="link" size="small">删除</Button>
                                    </Popconfirm>
                                </div>
                            ))}
                        </div>
                    )}
                </Spin>
            </div>

            <footer className="cart-footer">
                <div className="cart-total">
                    合计：<span>¥{total.toFixed(2)}</span>
                </div>
                <Button
                    type="primary"
                    size="large"
                    disabled={total <= 0}
                    onClick={() => message.info('订单模块开发中')}
                >
                    去结算
                </Button>
            </footer>
        </div>
    )
}
