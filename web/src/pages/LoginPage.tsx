import { Boxes } from 'lucide-react'
import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../lib/api'

const ACCOUNT_STORAGE_KEY = 'llm-wiki.login-account.v1'

/** 企业登录页，使用自定义 JWT 会话完成认证并保持界面简洁。 */
export default function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [account, setAccount] = useState(() => localStorage.getItem(ACCOUNT_STORAGE_KEY) ?? (import.meta.env.DEV ? '1' : ''))
  const [password, setPassword] = useState(import.meta.env.DEV ? '1' : '')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (user) return <Navigate to="/dashboard" replace />

  /** 提交登录并只持久化非敏感账号偏好，密码始终留在当前页面内存。 */
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      await login(account, password)
      localStorage.setItem(ACCOUNT_STORAGE_KEY, account.trim())
      navigate('/dashboard')
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <section className="login-story">
        <div className="login-brand"><span className="brand-mark"><Boxes size={21} /></span>LLM Wiki</div>
        <div className="login-copy">
          <h1>企业知识 Wiki</h1>
          <p>共同维护，持续更新，全程可追溯。</p>
        </div>
      </section>
      <section className="login-form-panel">
        <form className="login-form" onSubmit={submit}>
          <div><h2>登录</h2></div>
          <label>账号<input type="text" autoComplete="username" value={account} onChange={(event) => setAccount(event.target.value)} placeholder="请输入账号" required /></label>
          <label>密码<input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="输入密码" required /></label>
          {error ? <div className="form-error">{error}</div> : null}
          <button className="button primary large" type="submit" disabled={loading}>{loading ? <span className="spinner small" /> : null}{loading ? '正在验证…' : '登录'}</button>
        </form>
      </section>
    </div>
  )
}
