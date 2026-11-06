import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Empty, Pagination, Progress, Spin, Tag, message } from 'antd'
import { getSeckillList, getSeckillOrderResult, submitSeckillOrder, type SeckillActivity, type SeckillOrderSubmitVO } from '../api/seckill'
import AddressSelectModal from '../components/AddressSelectModal'
import ImageBox from '../components/ImageBox'
import SearchBar from '../components/SearchBar'
import UserNav from '../components/UserNav'
import { formatCountdown } from '../utils/countdown'
import './Seckill.css'

const PAGE_SIZE = 8
const POLL_INTERVAL_MS = 10_000

const PHASE_TEXT: Record<number, string> = {
    0: '即将开始',
    1: '进行中',
    2: '已结束',
    3: '已售罄',
}

function countdownText(activity: SeckillActivity, now: number): string {
    if (activity.phase === 2) return '活动已结束'
    if (activity.phase === 3) return '已售罄'
    if (activity.phase === 0) {
        const ms = new Date(activity.startTime).getTime() - now
        return ms > 0 ? `距开始 ${formatCountdown(ms)}` : '即将开始'
    }
    const ms = new Date(activity.endTime).getTime() - now
    return ms > 0 ? `距结束 ${formatCountdown(ms)}` : '即将结束'
}

const RESULT_POLL_INTERVAL_MS = 400
const RESULT_POLL_TIMEOUT_MS = 8000

async function pollSeckillResult(token: string): Promise<SeckillOrderSubmitVO | null> {
    const deadline = Date.now() + RESULT_POLL_TIMEOUT_MS
    while (Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, RESULT_POLL_INTERVAL_MS))
        const result = await getSeckillOrderResult(token)
        if (result.status !== 'PENDING') {
            return result
        }
    }
    return null
}

export default function SeckillPage() {
    const navigate = useNavigate()
    const [searchParams, setSearchParams] = useSearchParams()
    const keyword = searchParams.get('keyword')?.trim() || undefined
    const [activities, setActivities] = useState<SeckillActivity[]>([])
    const [total, setTotal] = useState(0)
    const [page, setPage] = useState(1)
    const [loading, setLoading] = useState(true)
    const [now, setNow] = useState(Date.now())

    const [buyTarget, setBuyTarget] = useState<SeckillActivity>()
    const [modalOpen, setModalOpen] = useState(false)
    const [submitting, setSubmitting] = useState(false)

    const loadList = useCallback(async () => {
        const data = await getSeckillList({ page, size: PAGE_SIZE, keyword })
        setActivities(data.records)
        setTotal(data.total)
    }, [page, keyword])

    useEffect(() => {
        setLoading(true)
        loadList()
            .catch((e: Error) => message.error(e.message || '秒杀活动加载失败'))
            .finally(() => setLoading(false))
    }, [loadList])

    // 库存和活动阶段会随下单变化，定时静默刷新
    useEffect(() => {
        const timer = setInterval(() => {
            loadList().catch(() => undefined)
        }, POLL_INTERVAL_MS)
        return () => clearInterval(timer)
    }, [loadList])

    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000)
        return () => clearInterval(timer)
    }, [])

    const openBuy = (activity: SeckillActivity) => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        setBuyTarget(activity)
        setModalOpen(true)
    }

    const handleSubmit = async (addressId: string) => {
        if (!buyTarget) {
            return
        }
        setSubmitting(true)
        try {
            const accepted = await submitSeckillOrder(buyTarget.id, { addressId })
            const result = accepted.status === 'PENDING' && accepted.token
                ? await pollSeckillResult(accepted.token)
                : accepted
            if (!result) {
                setModalOpen(false)
                message.info('已进入排队，请稍后在「我的订单」查看结果')
                loadList().catch(() => undefined)
                return
            }
            if (result.status === 'SUCCESS' && result.orderId) {
                message.success('抢购成功，请尽快支付')
                setModalOpen(false)
                navigate(`/order/${result.orderId}`)
                return
            }
            message.error(result.message || '秒杀失败')
            loadList().catch(() => undefined)
        } catch (e) {
            message.error((e as Error).message || '秒杀失败')
            loadList().catch(() => undefined)
        } finally {
            setSubmitting(false)
        }
    }

    const handleSearch = (kw: string) => {
        const next = new URLSearchParams(searchParams)
        if (kw) {
            next.set('keyword', kw)
        } else {
            next.delete('keyword')
        }
        setSearchParams(next)
        setPage(1)
    }

    return (
        <div className="seckill-page">
            <header className="seckill-header">
                <Link to="/home" className="seckill-logo">DoroShop</Link>
                <span className="seckill-header-title">限时秒杀</span>
                <div className="seckill-search">
                    <SearchBar
                        initialValue={keyword ?? ''}
                        onSearch={handleSearch}
                        placeholder="搜索秒杀商品"
                    />
                </div>
                <nav className="seckill-nav">
                    <Link to="/orders">我的订单</Link>
                    <Link to="/cart">购物车</Link>
                    <Link to="/home">首页</Link>
                    <UserNav />
                </nav>
            </header>

            <main className="seckill-main">
                <section className="seckill-hero">
                    <h1>限时秒杀</h1>
                    <p>秒杀价仅在活动时间内有效，每人每场限购 1 件</p>
                </section>

                <Spin spinning={loading}>
                    {activities.length === 0 ? (
                        <Empty
                            description={keyword ? `没有找到与“${keyword}”相关的秒杀活动` : '暂无秒杀活动'}
                            className="seckill-empty"
                        />
                    ) : (
                        <div className="seckill-grid">
                            {activities.map(activity => {
                                const sold = activity.totalStock - activity.availableStock
                                const percent = activity.totalStock > 0
                                    ? Math.min(100, Math.round((sold / activity.totalStock) * 100))
                                    : 100
                                const buyable = activity.phase === 1
                                return (
                                    <article
                                        key={activity.id}
                                        className={`seckill-card ${buyable ? '' : 'seckill-card-inactive'}`}
                                    >
                                        <div className="seckill-cover">
                                            <ImageBox
                                                src={activity.image}
                                                label={activity.productName || activity.name}
                                            />
                                        </div>
                                        <div className="seckill-card-body">
                                            <div className="seckill-name">{activity.name}</div>
                                            <div className="seckill-product">
                                                {activity.productName}
                                                {activity.specDesc ? ` · ${activity.specDesc}` : ''}
                                            </div>
                                            <div className="seckill-price-row">
                                                <span className="seckill-price">
                                                    ¥{Number(activity.seckillPrice).toFixed(2)}
                                                </span>
                                                {activity.originalPrice != null && (
                                                    <span className="seckill-original">
                                                        ¥{Number(activity.originalPrice).toFixed(2)}
                                                    </span>
                                                )}
                                                <Tag color={buyable ? 'red' : 'default'}>
                                                    {PHASE_TEXT[activity.phase] ?? '未知'}
                                                </Tag>
                                            </div>
                                            <Progress
                                                percent={percent}
                                                showInfo={false}
                                                strokeColor="#ff4d4f"
                                                railColor="#ffe1e1"
                                            />
                                            <div className="seckill-meta">
                                                <span>已抢 {sold} / {activity.totalStock}</span>
                                                <span className="seckill-countdown">
                                                    {countdownText(activity, now)}
                                                </span>
                                            </div>
                                            <Button
                                                type="primary"
                                                danger
                                                block
                                                disabled={!buyable}
                                                onClick={() => openBuy(activity)}
                                            >
                                                {buyable ? '立即抢购' : PHASE_TEXT[activity.phase]}
                                            </Button>
                                        </div>
                                    </article>
                                )
                            })}
                        </div>
                    )}
                </Spin>

                <Pagination
                    current={page}
                    pageSize={PAGE_SIZE}
                    total={total}
                    onChange={setPage}
                    style={{ textAlign: 'center', marginTop: 24 }}
                />
            </main>

            <AddressSelectModal
                open={modalOpen}
                title="确认秒杀下单"
                confirmText="确认抢购"
                submitting={submitting}
                summary={buyTarget ? (
                    <div className="seckill-modal-product">
                        <span>{buyTarget.productName}</span>
                        <span className="seckill-modal-price">
                            ¥{Number(buyTarget.seckillPrice).toFixed(2)}
                        </span>
                    </div>
                ) : undefined}
                onCancel={() => setModalOpen(false)}
                onConfirm={handleSubmit}
            />
        </div>
    )
}
