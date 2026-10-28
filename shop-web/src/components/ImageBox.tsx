import { useEffect, useState } from 'react'

interface ImageBoxProps {
    src?: string | null
    label: string
    className?: string
}

/** 图片不存在或加载失败时显示渐变占位块，避免裂图 */
export default function ImageBox({ src, label, className }: ImageBoxProps) {
    const [failed, setFailed] = useState(false)

    useEffect(() => {
        setFailed(false)
    }, [src])

    if (!src || failed) {
        return <div className={`image-placeholder ${className ?? ''}`}>{label.slice(0, 1)}</div>
    }
    return (
        <img
            className={className}
            src={src}
            alt={label}
            onError={() => setFailed(true)}
        />
    )
}
