import { useEffect } from 'react'
import { Button, Form, Input, Modal, Switch, message } from 'antd'
import { addAddress, updateAddress, type AddressSaveDTO, type UserAddressVO } from '../api/address'

interface AddressFormModalProps {
    open: boolean
    initial?: UserAddressVO
    onCancel: () => void
    onSaved: () => void
}

/** 新增 / 编辑收货地址弹窗，供地址页与下单弹窗复用。 */
export default function AddressFormModal({ open, initial, onCancel, onSaved }: AddressFormModalProps) {
    const [form] = Form.useForm()

    useEffect(() => {
        if (!open) return
        if (initial) {
            form.setFieldsValue({
                receiverName: initial.receiverName,
                receiverPhone: initial.receiverPhone,
                province: initial.province,
                city: initial.city,
                district: initial.district,
                detail: initial.detail,
                isDefault: initial.isDefault === 1,
            })
        } else {
            form.resetFields()
        }
    }, [open, initial, form])

    const handleFinish = async () => {
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
            if (initial) {
                await updateAddress(initial.id, dto)
                message.success('地址已更新')
            } else {
                await addAddress(dto)
                message.success('地址已添加')
            }
            onSaved()
        } catch (e) {
            if (e instanceof Error) {
                message.error(e.message || '保存地址失败')
            }
        }
    }

    return (
        <Modal
            title={initial ? '编辑收货地址' : '新增收货地址'}
            open={open}
            onCancel={onCancel}
            footer={null}
            destroyOnHidden
        >
            <Form form={form} layout="vertical" onFinish={handleFinish}>
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
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 20 }}>
                    <Button onClick={onCancel}>取消</Button>
                    <Button type="primary" htmlType="submit">保存</Button>
                </div>
            </Form>
        </Modal>
    )
}
