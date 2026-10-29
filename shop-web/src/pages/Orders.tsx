import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Empty, Pagination, Spin, Tabs, Tag, message } from 'antd'
import { getOrderList, type OrderListItemVO } from '../api/order'
import { orderStatusColor, orderStatusText } from '../utils/orderStatus'
import ImageBox from '../components/ImageBox'
import './Orders.css'

const PAGE_SIZE = 5

const TABS = [
    { key: 'all', label: '全部' },
    { key: '0', label: '待支付' },
    { key: '1', label: '已支付' },
    { key: '4', label: '已取消' },
]

export default function OrdersPage() {
    const navigate = useNavigate()
    const [tab, setTab] = useState('all')
    const [page, setPage] = useState(1)
    const [total, setTotal] = useState(0)
    const [orders, setOrders] = useState<OrderListItemVO[]>([])
    const [loading, setLoading] = useState(true)

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        setLoading(true)
        getOrderList({
            page,
            size: PAGE_SIZE,
            status: tab === 'all' ? undefined : Number(tab),
        })
            .then(data => {
                setOrders(data.records)
                setTotal(data.total)
            })
            .catch((e: Error) => message.error(e.message || '订单加载失败'))
            .finally(() => setLoading(false))
    }, [tab, page, navigate])

    // 切换 Tab 时回到第一页
    const handleTabChange = (key: string) => {
        setTab(key)
        setPage(1)
    }

    return (
        <div className="orders-page">
            <header className="orders-header">
                <Link to="/home">← 返回首页</Link>
                <span className="orders-title">我的订单</span>
            </header>

            <div className="orders-tabs">
                <Tabs activeKey={tab} items={TABS} onChange={handleTabChange} />
            </div>

            <Spin spinning={loading}>
                {orders.length === 0 ? (
                    <div className="orders-empty">
                        <Empty description="暂无订单">
                            <Link to="/home">去逛逛</Link>
                        </Empty>
                    </div>
                ) : (
                    <div className="orders-list">
                        {orders.map(order => (
                            <div className="order-card" key={order.id}>
                                <div className="order-card-head">
                                    <span className="order-card-no">订单号：{order.orderNo}</span>
                                    <span className="order-card-time">
                                        {order.createTime?.replace('T', ' ')}
                                    </span>
                                    <Tag color={orderStatusColor(order.status)}>
                                        {orderStatusText(order.status)}
                                    </Tag>
                                </div>
                                <div className="order-card-body">
                                    <div className="order-card-thumb">
                                        <ImageBox src={order.firstItemImage} label={order.firstItemName ?? '商品'} />
                                    </div>
                                    <div className="order-card-info">
                                        <div className="order-card-name">{order.firstItemName ?? '商品已失效'}</div>
                                        <div className="order-card-count">共 {order.itemCount} 件</div>
                                    </div>
                                    <div className="order-card-amount">
                                        ¥{Number(order.payAmount).toFixed(2)}
                                    </div>
                                    <div className="order-card-actions">
                                        <Button size="small" onClick={() => navigate(`/order/${order.id}`)}>
                                            查看详情
                                        </Button>
                                        {order.status === 0 && (
                                            <Button
                                                size="small"
                                                type="primary"
                                                onClick={() => navigate(`/order/${order.id}`)}
                                            >
                                                去支付
                                            </Button>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </Spin>

            {total > PAGE_SIZE && (
                <div className="orders-pagination">
                    <Pagination
                        current={page}
                        pageSize={PAGE_SIZE}
                        total={total}
                        onChange={setPage}
                    />
                </div>
            )}
        </div>
    )
}
