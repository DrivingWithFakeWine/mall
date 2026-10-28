import axios from 'axios'
import type { components } from './schema'

export type CategoryVO = components['schemas']['CategoryVO']



// 后端未就绪期间使用：与 data.sql 真实数据一致
const MOCK_TREE: CategoryVO[] = [
    {
        id: '1900000000000000001', name: '数码', sort: 1,
        children: [
            { id: '1900000000000000011', name: '手机', sort: 1, children: [] },
            { id: '1900000000000000012', name: '耳机', sort: 2, children: [] },
        ],
    },
    {
        id: '1900000000000000002', name: '服饰', sort: 2,
        children: [{ id: '1900000000000000021', name: '男装', sort: 1, children: [] }],
    },
    {
        id: '1900000000000000003', name: '食品', sort: 3,
        children: [{ id: '1900000000000000031', name: '零食', sort: 1, children: [] }],
    },
]

const USE_MOCK = false   // 后端就绪后改 false

export async function getCategoryTree(): Promise<CategoryVO[]> {
    if (USE_MOCK) return MOCK_TREE
    const res = await axios.get<{ code: number; message: string; data: CategoryVO[] }>('/api/category/tree')
    return res.data.data
}