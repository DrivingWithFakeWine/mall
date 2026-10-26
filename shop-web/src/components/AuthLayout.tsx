import type { ReactNode } from 'react'
import './AuthLayout.css'

interface AuthLayoutProps {
  children: ReactNode
}

export default function AuthLayout({ children }: AuthLayoutProps) {
  return (
    <div className="auth-page">
      <div className="auth-brand">
        <h1>DoroShop</h1>
        <p>品质好物 · 极速送达</p>
      </div>
      <div className="auth-panel">
        <div className="auth-card">{children}</div>
      </div>
      <div className="auth-footer">版权所有 © 2026 DoroShop · 仅用于学习交流</div>
    </div>
  )
}
