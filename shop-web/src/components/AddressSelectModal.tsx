import { useEffect, useState, type ReactNode } from 'react'
import { Alert, Button, Divider, Modal, Radio, Spin, message } from 'antd'
import { getAddressList, type UserAddressVO } from '../api/address'
import AddressFormModal from './AddressFormModal'

interface AddressSelectModalProps {
    open: boolean
    title?: string
    confirmText?: string
    submitting?: boolean
    /** 地址列表上方展示的商品摘要等自定义内容 */
    summary?: ReactNode
    onCancel: () => void
    onConfirm: (addressId: string) => void
}

/** 选择收货地址弹窗（内含新增地址），下单场景复用。 */
export default function AddressSelectModal({
    open,
    title = '选择收货地址',
    confirmText = '确定',
    submitting = false,
    summary,
    onCancel,
    onConfirm,
}: AddressSelectModalProps) {
    const [addresses, setAddresses] = useState<UserAddressVO[]>([])
    const [addressId, setAddressId] = useState<string>()
    const [loading, setLoading] = useState(false)
    const [formOpen, setFormOpen] = useState(false)
    const [previousIds, setPreviousIds] = useState<string[]>([])

    const loadAddresses = async (selectNewest = false) => {
        setLoading(true)
        try {
            const list = await getAddressList()
            setAddresses(list)
            if (selectNewest) {
                const added = list.find(a => !previousIds.includes(a.id))
                setAddressId(added?.id ?? list[0]?.id)
            } else {
                setAddressId(prev => prev ?? list.find(a => a.isDefault === 1)?.id ?? list[0]?.id)
            }
        } catch (e) {
            message.error((e as Error).message || '地址加载失败')
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        if (!open) return
        setPreviousIds([])
        setAddressId(undefined)
        loadAddresses()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open])

    const handleAddAddress = () => {
        setPreviousIds(addresses.map(a => a.id))
        setFormOpen(true)
    }

    const handleSaved = () => {
        setFormOpen(false)
        loadAddresses(true)
    }

    return (
        <>
            <Modal
                title={title}
                open={open}
                onCancel={onCancel}
                footer={null}
                destroyOnHidden
            >
                {summary}

                <Spin spinning={loading}>
                    {addresses.length === 0 ? (
                        <Alert type="warning" showIcon message="还没有收货地址，请先添加" />
                    ) : (
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
                    )}
                </Spin>

                <Divider />
                <Button type="dashed" block onClick={handleAddAddress}>＋ 新增地址</Button>
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 20 }}>
                    <Button onClick={onCancel}>取消</Button>
                    <Button
                        type="primary"
                        loading={submitting}
                        disabled={!addressId}
                        onClick={() => addressId && onConfirm(addressId)}
                    >
                        {confirmText}
                    </Button>
                </div>
            </Modal>

            <AddressFormModal
                open={formOpen}
                onCancel={() => setFormOpen(false)}
                onSaved={handleSaved}
            />
        </>
    )
}
