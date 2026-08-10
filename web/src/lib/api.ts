import type { ApiErrorPayload, AuthResponse } from '../types'

let accessToken = ''
let refreshPromise: Promise<AuthResponse> | null = null
let authUpdateListener: ((auth: AuthResponse | null) => void) | null = null

/** 带状态码的 API 错误，页面可按 code 呈现精确反馈。 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

/** 更新仅保存在内存中的短期访问令牌。 */
export function setAccessToken(token: string): void {
  accessToken = token
}

/** 注册认证刷新监听器，避免 API 层直接依赖 React。 */
export function setAuthUpdateListener(listener: (auth: AuthResponse | null) => void): void {
  authUpdateListener = listener
}

/**
 * 统一 API 请求：注入 Bearer、解析错误，并在 401 时只发起一次共享刷新请求。
 */
export async function apiRequest<T>(path: string, init: RequestInit = {}, canRetry = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (!(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const response = await fetch(path, { ...init, headers, credentials: 'include' })
  if (response.status === 401 && canRetry && !path.startsWith('/api/auth/')) {
    try {
      const refreshed = await refreshSession()
      setAccessToken(refreshed.accessToken)
      authUpdateListener?.(refreshed)
      return apiRequest<T>(path, init, false)
    } catch {
      accessToken = ''
      authUpdateListener?.(null)
    }
  }
  if (!response.ok) {
    let payload: Partial<ApiErrorPayload> = {}
    try {
      payload = (await response.json()) as Partial<ApiErrorPayload>
    } catch {
      // 非 JSON 错误仍使用 HTTP 状态生成稳定错误。
    }
    throw new ApiError(response.status, payload.code ?? 'HTTP_ERROR', payload.message ?? response.statusText)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
  return (await response.json()) as T
}

/** 通过 HttpOnly Cookie 恢复会话，并合并并发刷新请求。 */
export function refreshSession(): Promise<AuthResponse> {
  if (!refreshPromise) {
    refreshPromise = fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(async (response) => {
        if (!response.ok) throw new ApiError(response.status, 'REFRESH_FAILED', '会话已过期')
        return (await response.json()) as AuthResponse
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

/** 浏览器下载需手动附带仅存内存的访问令牌。 */
export async function downloadAuthenticated(path: string, filename: string): Promise<void> {
  const response = await fetch(path, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    credentials: 'include',
  })
  if (!response.ok) throw new ApiError(response.status, 'DOWNLOAD_FAILED', '下载失败')
  const disposition = response.headers.get('content-disposition')
  const encodedFilename = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const headerFilename = encodedFilename ? decodeURIComponent(encodedFilename) : disposition?.match(/filename="?([^";]+)"?/i)?.[1]
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = headerFilename || filename
  anchor.click()
  URL.revokeObjectURL(url)
}
