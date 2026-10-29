export const ORDER_STATUS: Record<number, { text: string; color: string }> = {
    0: { text: '待支付', color: 'orange' },
    1: { text: '已支付', color: 'blue' },
    2: { text: '已发货', color: 'cyan' },
    3: { text: '已完成', color: 'green' },
    4: { text: '已取消', color: 'default' },
}

export function orderStatusText(status: number): string {
    return ORDER_STATUS[status]?.text ?? '未知状态'
}

export function orderStatusColor(status: number): string {
    return ORDER_STATUS[status]?.color ?? 'default'
}
