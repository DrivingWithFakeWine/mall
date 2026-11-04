import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button, Empty, Popconfirm, Spin, Tag, message } from 'antd'
import { getAddressList, removeAddress, updateAddress, type UserAddressVO } from '../api/address'
import AddressFormModal from '../components/AddressFormModal'
import UserNav from '../components/UserNav'
import './Addresses.css'

export default function AddressesPage() {
    const navigate = useNavigate()
    const [addresses, setAddresses] = useState<UserAddressVO[]>([])
    const [loading, setLoading] = useState(true)
    const [formOpen, setFormOpen] = useState(false)
    const [editing, setEditing] = useState<UserAddressVO>()

    const load = useCallback(async () => {
        const list = await getAddressList()
        setAddresses(list)
    }, [])

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            message.warning('请先登录')
            navigate('/login')
            return
        }
        setLoading(true)
        load()
            .catch((e: Error) => message.error(e.message || '地址加载失败'))
            .finally(() => setLoading(false))
    }, [navigate, load])

    const openAdd = () => {
        setEditing(undefined)
        setFormOpen(true)
    }

    const openEdit = (address: UserAddressVO) => {
        setEditing(address)
        setFormOpen(true)
    }

    const handleSaved = async () => {
        setFormOpen(false)
        try {
            await load()
        } catch (e) {
            message.error((e as Error).message || '地址刷新失败')
        }
    }

    const handleSetDefault = async (address: UserAddressVO) => {
        try {
            await updateAddress(address.id, {
                receiverName: address.receiverName,
                receiverPhone: address.receiverPhone,
                province: address.province,
                city: address.city,
                district: address.district,
                detail: address.detail,
                isDefault: 1,
            })
            message.success('已设为默认地址')
            await load()
        } catch (e) {
            message.error((e as Error).message || '设置默认地址失败')
        }
    }

    const handleRemove = async (address: UserAddressVO) => {
        try {
            await removeAddress(address.id)
            message.success('地址已删除')
            await load()
        } catch (e) {
            message.error((e as Error).message || '删除地址失败')
        }
    }

    return (
        <div className="addr-page">
            <header className="addr-header">
                <Link to="/home" className="addr-logo">DoroShop</Link>
                <span className="addr-title">收货地址</span>
                <div className="addr-nav">
                    <Link to="/account">账户中心</Link>
                    <Link to="/orders">我的订单</Link>
                    <UserNav />
                </div>
            </header>

            <main className="addr-main">
                <div className="addr-toolbar">
                    <span className="addr-count">共 {addresses.length} 条地址</span>
                    <Button type="primary" onClick={openAdd}>新增地址</Button>
                </div>

                <Spin spinning={loading}>
                    {addresses.length === 0 ? (
                        <Empty description="还没有收货地址" className="addr-empty" />
                    ) : (
                        <div className="addr-grid">
                            {addresses.map(address => (
                                <article className="addr-card" key={address.id}>
                                    <div className="addr-card-head">
                                        <span className="addr-name">{address.receiverName}</span>
                                        <span className="addr-phone">{address.receiverPhone}</span>
                                        {address.isDefault === 1 && <Tag color="red">默认</Tag>}
                                    </div>
                                    <div className="addr-card-detail">
                                        {address.province} {address.city} {address.district} {address.detail}
                                    </div>
                                    <div className="addr-card-actions">
                                        <Button type="link" size="small" onClick={() => openEdit(address)}>
                                            编辑
                                        </Button>
                                        {address.isDefault !== 1 && (
                                            <Button type="link" size="small" onClick={() => handleSetDefault(address)}>
                                                设为默认
                                            </Button>
                                        )}
                                        <Popconfirm title="确定删除该地址吗？" onConfirm={() => handleRemove(address)}>
                                            <Button type="link" size="small" danger>删除</Button>
                                        </Popconfirm>
                                    </div>
                                </article>
                            ))}
                        </div>
                    )}
                </Spin>
            </main>

            <AddressFormModal
                open={formOpen}
                initial={editing}
                onCancel={() => setFormOpen(false)}
                onSaved={handleSaved}
            />
        </div>
    )
}
