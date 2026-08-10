import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { apiRequest, refreshSession, setAccessToken, setAuthUpdateListener } from '../lib/api'
import type { AuthResponse, CurrentUser } from '../types'

interface AuthContextValue {
  user: CurrentUser | null
  ready: boolean
  login: (identifier: string, password: string) => Promise<void>
  logout: () => Promise<void>
  switchWorkspace: (workspaceId: string) => Promise<void>
  hasPermission: (permission: string) => boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * 管理内存访问令牌和 HttpOnly 刷新会话；页面刷新时先恢复会话再渲染受保护路由。
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    setAuthUpdateListener((auth) => {
      setUser(auth?.user ?? null)
      if (auth) setAccessToken(auth.accessToken)
    })
    refreshSession()
      .then((auth) => {
        setAccessToken(auth.accessToken)
        setUser(auth.user)
      })
      .catch(() => setUser(null))
      .finally(() => setReady(true))
    return () => setAuthUpdateListener(() => undefined)
  }, [])

  /** 使用账号密码创建会话。 */
  const login = useCallback(async (identifier: string, password: string) => {
    const auth = await apiRequest<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ identifier, password }),
    })
    setAccessToken(auth.accessToken)
    setUser(auth.user)
  }, [])

  /** 撤销会话并清理内存令牌。 */
  const logout = useCallback(async () => {
    await apiRequest<void>('/api/auth/logout', { method: 'POST' })
    setAccessToken('')
    setUser(null)
  }, [])

  /** 切换固定在服务端会话上的空间上下文。 */
  const switchWorkspace = useCallback(async (workspaceId: string) => {
    const auth = await apiRequest<AuthResponse>('/api/auth/switch-workspace', {
      method: 'POST',
      body: JSON.stringify({ workspaceId }),
    })
    setAccessToken(auth.accessToken)
    setUser(auth.user)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      ready,
      login,
      logout,
      switchWorkspace,
      hasPermission: (permission) => user?.permissions.includes(permission) ?? false,
    }),
    [user, ready, login, logout, switchWorkspace],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** 读取认证上下文。 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
