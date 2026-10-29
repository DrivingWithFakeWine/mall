import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Divider, Empty, Form, Input, Modal, Radio, Spin, Switch, message } from 'antd'
import { confirmOrder, submitOrder, type OrderConfirmVO } from '../api/order'
import { addAddress, getAddressList, type AddressSaveDTO, type UserAddressVO } from '../api/address'
import ImageBox from '../components/ImageBox'
import './Checkout.css'

export default function CheckoutPage() {
    const navigate = useNavigate()
    const [loading, setLoading] = useState(true)
    const [submitting, setSubmitting] = useState(false)
    const [confirm, setConfirm] = useState<OrderConfirmVO>()
    const [addresses, setAddresses] = useState<UserAddressVO[]>([])
    const [addressId, setAddressId] = useState<string>()
    const [modalOpen, setModalOpen] = useState(false)
    const [formMode, setFormMode] = useState(false)
    const [form] = Form.useForm()

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        Promise.all([confirmOrder(), getAddressList()])
            .then(([confirmData, list]) => {
                setConfirm(confirmData)
                setAddresses(list)
                setAddressId(confirmData.address?.id ?? list[0]?.id)
            })
            .catch((e: Error) => message.error(e.message || '结算信息加载失败'))
            .finally(() => setLoading(false))
    }, [navigate])

    const selectedAddress = addresses.find(a => a.id === addressId)

    const openModal = () => {
        setFormMode(false)
        setModalOpen(true)
    }

    const handleAddAddress = async () => {
        try {
            const values = await form.validateFields()
            const dto: AddressSaveDTO = {
                receiverName: values.receiverName,
                receiverPhone: values.receiverPhone,
                province: values.province,
                city: values.city,
                district: values.district,
                detail: values.detail,
                isDefault: values.isDefault ? 1 : 0,
            }
            await addAddress(dto)
            message.success('地址已添加')
            const beforeIds = new Set(addresses.map(a => a.id))
            const list = await getAddressList()
            setAddresses(list)
            // 选中刚新增的那条；找不到时退回列表第一条
            const added = list.find(a => !beforeIds.has(a.id))
            setAddressId(added?.id ?? list[0]?.id)
            setFormMode(false)
            form.resetFields()
        } catch (e) {
            if (e instanceof Error) {
                message.error(e.message || '添加地址失败')
            }
        }
    }

    const handleSubmit = async () => {
        if (!addressId) {
            message.warning('请先添加收货地址')
            return
        }
        setSubmitting(true)
        try {
            const order = await submitOrder(addressId)
            message.success('下单成功')
            navigate(`/order/${order.orderId}`)
        } catch (e) {
            message.error((e as Error).message || '下单失败')
        } finally {
            setSubmitting(false)
        }
    }

    if (loading) {
        return <div className="checkout-loading"><Spin size="large" /></div>
    }
    if (!confirm) {
        return (
            <div className="checkout-loading">
                <Empty description="没有可结算的商品" />
                <Link to="/cart">返回购物车</Link>
            </div>
        )
    }

    return (
        <div className="checkout-page">
            <header className="checkout-header">
                <Link to="/cart">← 返回购物车</Link>
                <span className="checkout-title">确认订单</span>
            </header>

            <section className="checkout-section">
                <div className="section-title">收货地址</div>
                {selectedAddress ? (
                    <div className="address-card">
                        <div className="address-main">
                            <span className="address-name">{selectedAddress.receiverName}</span>
                            <span className="address-phone">{selectedAddress.receiverPhone}</span>
                            {selectedAddress.isDefault === 1 && <span className="address-default">默认</span>}
                        </div>
                        <div className="address-detail">
                            {selectedAddress.province} {selectedAddress.city} {selectedAddress.district} {selectedAddress.detail}
                        </div>
                    </div>
                ) : (
                    <Alert type="warning" showIcon message="还没有收货地址，请先添加" />
                )}
                <Button style={{ marginTop: 12 }} onClick={openModal}>
                    {selectedAddress ? '更换地址' : '添加地址'}
                </Button>
            </section>

            <section className="checkout-section">
                <div className="section-title">商品清单</div>
                {confirm.items.map(item => (
                    <div className="checkout-item" key={item.cartId}>
                        <div className="checkout-thumb">
                            <ImageBox src={item.image} label={item.name} />
                        </div>
                        <div className="checkout-item-info">
                            <div className="checkout-item-name">{item.name}</div>
                            <div className="checkout-item-spec">{item.specDesc}</div>
                        </div>
                        <div className="checkout-item-price">¥{Number(item.price).toFixed(2)}</div>
                        <div className="checkout-item-qty">× {item.quantity}</div>
                        <div className="checkout-item-subtotal">¥{Number(item.subtotal).toFixed(2)}</div>
                    </div>
                ))}
            </section>

            <footer className="checkout-footer">
                <div className="checkout-total">
                    合计：<span>¥{Number(confirm.totalAmount).toFixed(2)}</span>
                </div>
                <Button
                    type="primary"
                    size="large"
                    loading={submitting}
                    disabled={!addressId}
                    onClick={handleSubmit}
                >
                    提交订单
                </Button>
            </footer>

            <Modal
                title={formMode ? '新增收货地址' : '选择收货地址'}
                open={modalOpen}
                onCancel={() => setModalOpen(false)}
                footer={null}
                destroyOnHidden
            >
                {formMode ? (
                    <Form form={form} layout="vertical" onFinish={handleAddAddress}>
                        <Form.Item name="receiverName" label="收货人" rules={[{ required: true, message: '请输入收货人' }]}>
                            <Input placeholder="收货人姓名" />
                        </Form.Item>
                        <Form.Item
                            name="receiverPhone"
                            label="手机号"
                            rules={[
                                { required: true, message: '请输入手机号' },
                                { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' },
                            ]}
                        >
                            <Input placeholder="11 位手机号" />
                        </Form.Item>
                        <Form.Item name="province" label="省份" rules={[{ required: true, message: '请输入省份' }]}>
                            <Input placeholder="如：北京市" />
                        </Form.Item>
                        <Form.Item name="city" label="城市" rules={[{ required: true, message: '请输入城市' }]}>
                            <Input placeholder="如：北京市" />
                        </Form.Item>
                        <Form.Item name="district" label="区/县" rules={[{ required: true, message: '请输入区县' }]}>
                            <Input placeholder="如：海淀区" />
                        </Form.Item>
                        <Form.Item name="detail" label="详细地址" rules={[{ required: true, message: '请输入详细地址' }]}>
                            <Input.TextArea rows={2} maxLength={255} placeholder="街道、门牌号" />
                        </Form.Item>
                        <Form.Item name="isDefault" label="设为默认地址" valuePropName="checked">
                            <Switch />
                        </Form.Item>
                        <div className="modal-actions">
                            <Button onClick={() => setFormMode(false)}>返回列表</Button>
                            <Button type="primary" htmlType="submit">保存地址</Button>
                        </div>
                    </Form>
                ) : (
                    <div>
                        <Radio.Group
                            value={addressId}
                            onChange={e => setAddressId(e.target.value)}
                            style={{ display: 'flex', flexDirection: 'column', gap: 12 }}
                        >
                            {addresses.map(address => (
                                <Radio key={address.id} value={address.id}>
                                    {address.receiverName} {address.receiverPhone}
                                    {' · '}
                                    {address.province}{address.city}{address.district}{address.detail}
                                    {address.isDefault === 1 ? '（默认）' : ''}
                                </Radio>
                            ))}
                        </Radio.Group>
                        <Divider />
                        <Button type="dashed" block onClick={() => setFormMode(true)}>
                            ＋ 新增地址
                        </Button>
                        <div className="modal-actions">
                            <Button type="primary" onClick={() => setModalOpen(false)}>确定</Button>
                        </div>
                    </div>
                )}
            </Modal>
        </div>
    )
}
