import { useEffect, useState } from 'react'
import { Input } from 'antd'
import './SearchBar.css'

interface SearchBarProps {
    initialValue?: string
    placeholder?: string
    onSearch: (keyword: string) => void
}

export default function SearchBar({ initialValue = '', placeholder = '搜索商品', onSearch }: SearchBarProps) {
    const [value, setValue] = useState(initialValue)

    useEffect(() => {
        setValue(initialValue)
    }, [initialValue])

    const trigger = (raw: string) => {
        const keyword = raw.trim()
        setValue(keyword)
        onSearch(keyword)
    }

    return (
        <div className="search-bar">
            <Input.Search
                value={value}
                placeholder={placeholder}
                allowClear
                enterButton="搜索"
                onChange={e => {
                    const next = e.target.value
                    setValue(next)
                    // 点清除按钮时同步清空结果
                    if (!next.trim() && value.trim()) {
                        onSearch('')
                    }
                }}
                onSearch={trigger}
            />
        </div>
    )
}
