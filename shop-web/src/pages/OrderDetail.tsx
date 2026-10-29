import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button, Descriptions, Empty, Popconfirm, Spin, Tag, message } from 'antd'
import { cancelOrder, getOrderDetail, payOrder, type OrderDetailVO } from '../api/order'
import { orderStatusColor, orderStatusText } from '../utils/orderStatus'
import ImageBox from '../components/ImageBox'
import './OrderDetail.css'

function formatCountdown(ms: number): string {
    if (ms <= 0) return '已超时'
    const totalSeconds = Math.floor(ms / 1000)
    const hours = Math.floor(totalSeconds / 3600)
    const minutes = Math.floor((totalSeconds % 3600) / 60)
    const seconds = totalSeconds % 60
    const pad = (n: number) => String(n).padStart(2, '0')
    return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

export default function OrderDetailPage() {
    const { id } = useParams<{ id: string }>()
    const navigate = useNavigate()
    const [detail, setDetail] = useState<OrderDetailVO>()
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string>()
    const [acting, setActing] = useState(false)
    const [now, setNow] = useState(Date.now())

    const load = async (orderId: string) => {
        const data = await getOrderDetail(orderId)
        setDetail(data)
        setError(undefined)
    }

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        if (!id) return
        setLoading(true)
        load(id)
            .catch((e: Error) => setError(e.message || '订单加载失败'))
            .finally(() => setLoading(false))
    }, [id, navigate])

    // 待支付订单每秒刷新倒计时
    useEffect(() => {
        if (detail?.status !== 0) return
        const timer = setInterval(() => setNow(Date.now()), 1000)
        return () => clearInterval(timer)
    }, [detail?.status])

    const remainingMs = useMemo(() => {
        if (!detail?.expireTime) return 0
        return new Date(detail.expireTime).getTime() - now
    }, [detail?.expireTime, now])

    const handlePay = async () => {
        if (!detail) return
        setActing(true)
        try {
            await payOrder(detail.id)
            message.success('支付成功')
            await load(detail.id)
        } catch (e) {
            message.error((e as Error).message || '支付失败')
        } finally {
            setActing(false)
        }
    }

    const handleCancel = async () => {
        if (!detail) return
        setActing(true)
        try {
            await cancelOrder(detail.id)
            message.success('订单已取消')
            await load(detail.id)
        } catch (e) {
            message.error((e as Error).message || '取消失败')
        } finally {
            setActing(false)
        }
    }

    if (loading) {
        return <div className="order-loading"><Spin size="large" /></div>
    }
    if (error || !detail) {
        return (
            <div className="order-loading">
                <Empty description={error ?? '订单不存在'} />
                <Link to="/home">返回首页</Link>
            </div>
        )
    }

    const isWaitPay = detail.status === 0

    return (
        <div className="order-page">
            <header className="order-header">
                <Link to="/home">← 返回首页</Link>
                <span className="order-title">订单详情</span>
                <Link to="/orders" style={{ marginLeft: 'auto', fontSize: 14 }}>我的订单</Link>
            </header>

            <section className="order-status-bar">
                <Tag color={orderStatusColor(detail.status)}>{orderStatusText(detail.status)}</Tag>
                {isWaitPay && (
                    <span className={remainingMs > 0 ? 'countdown' : 'countdown expired'}>
                        {remainingMs > 0
                            ? `剩余支付时间 ${formatCountdown(remainingMs)}`
                            : '支付已超时，请重新下单'}
                    </span>
                )}
                {isWaitPay && (
                    <div className="order-actions">
                        <Popconfirm title="确定取消该订单吗？" onConfirm={handleCancel}>
                            <Button disabled={acting}>取消订单</Button>
                        </Popconfirm>
                        <Button type="primary" loading={acting} onClick={handlePay}>
                            立即支付 ¥{Number(detail.payAmount).toFixed(2)}
                        </Button>
                    </div>
                )}
            </section>

            <section className="order-section">
                <div className="section-title">订单信息</div>
                <Descriptions column={2} size="small">
                    <Descriptions.Item label="订单号">{detail.orderNo}</Descriptions.Item>
                    <Descriptions.Item label="下单时间">{detail.createTime?.replace('T', ' ')}</Descriptions.Item>
                    <Descriptions.Item label="订单金额">¥{Number(detail.totalAmount).toFixed(2)}</Descriptions.Item>
                    <Descriptions.Item label="应付金额">¥{Number(detail.payAmount).toFixed(2)}</Descriptions.Item>
                    {detail.payTime && (
                        <Descriptions.Item label="支付时间">{detail.payTime.replace('T', ' ')}</Descriptions.Item>
                    )}
                    {detail.cancelTime && (
                        <Descriptions.Item label="取消时间">{detail.cancelTime.replace('T', ' ')}</Descriptions.Item>
                    )}
                    {detail.remark && (
                        <Descriptions.Item label="备注" span={2}>{detail.remark}</Descriptions.Item>
                    )}
                </Descriptions>
            </section>

            <section className="order-section">
                <div className="section-title">商品清单</div>
                {detail.items.map(item => (
                    <div className="order-item" key={item.id}>
                        <div className="order-thumb">
                            <ImageBox src={item.image} label={item.productName} />
                        </div>
                        <div className="order-item-info">
                            <div className="order-item-name">{item.productName}</div>
                            <div className="order-item-spec">{item.specDesc}</div>
                        </div>
                        <div className="order-item-price">¥{Number(item.price).toFixed(2)}</div>
                        <div className="order-item-qty">× {item.quantity}</div>
                        <div className="order-item-subtotal">¥{Number(item.totalAmount).toFixed(2)}</div>
                    </div>
                ))}
            </section>

            {detail.address && (
                <section className="order-section">
                    <div className="section-title">收货信息</div>
                    <div className="order-address">
                        <div>
                            <span className="order-address-name">{detail.address.receiverName}</span>
                            <span className="order-address-phone">{detail.address.receiverPhone}</span>
                        </div>
                        <div className="order-address-detail">
                            {detail.address.province} {detail.address.city} {detail.address.district} {detail.address.detail}
                        </div>
                    </div>
                </section>
            )}
        </div>
    )
}
