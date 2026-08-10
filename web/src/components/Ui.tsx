import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react'
import type { ReactNode } from 'react'

/** 页面标题区，保持所有业务页的操作布局一致。 */
export function PageHeader({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>{eyebrow ? <span className="eyebrow">{eyebrow}</span> : null}<h1>{title}</h1>{description ? <p>{description}</p> : null}</div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  )
}

/** 风险/状态徽标，颜色与语义固定。 */
export function Badge({ value }: { value: string }) {
  const kind = value.toLowerCase().replaceAll('_', '-')
  return <span className={`badge badge-${kind}`}>{badgeLabels[value] ?? value}</span>
}

/** 后端机器状态对应的统一中文显示文本。 */
const badgeLabels: Record<string, string> = {
  PENDING: '待处理', RUNNING: '处理中', PROCESSING: '处理中', SUCCEEDED: '成功', FAILED: '失败',
  SKIPPED: '已跳过', APPROVED: '已批准', REJECTED: '已驳回', SUPERSEDED: '已失效', DRAFT: '草稿',
  ACTIVE: '正常', READY: '就绪', DEAD: '已终止', ARCHIVED: '已归档', INVITED: '待加入',
  HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险', SYSTEM: '系统', CUSTOM: '自定义',
  ENABLED: '已启用', DISABLED: '未启用', TOPIC: '主题', ENTITY: '实体', SYNTHESIS: '综合',
  README: '说明', CONTEXT: '上下文',
  CREATE: '新增', UPDATE: '更新', CREATE_PAGE: '新增页面', UPDATE_PAGE: '更新页面',
}

/** 统一空状态。 */
export function EmptyState({ icon, title, description, action }: { icon?: ReactNode; title: string; description?: string; action?: ReactNode }) {
  return <div className="empty-state">{icon}<h3>{title}</h3>{description ? <p>{description}</p> : null}{action}</div>
}

/** 统一加载骨架。 */
export function LoadingBlock({ rows = 4 }: { rows?: number }) {
  return <div className="loading-block">{Array.from({ length: rows }, (_, index) => <span key={index} style={{ width: `${94 - index * 7}%` }} />)}</div>
}

/** 提示条。 */
export function Notice({ kind = 'info', children }: { kind?: 'info' | 'success' | 'warning' | 'error'; children: ReactNode }) {
  const Icon = kind === 'success' ? CheckCircle2 : kind === 'warning' ? AlertTriangle : kind === 'error' ? XCircle : Info
  return <div className={`notice notice-${kind}`}><Icon size={17} />{children}</div>
}

/** 可访问的模态容器。 */
export function Modal({ title, children, onClose, wide = false }: { title: string; children: ReactNode; onClose: () => void; wide?: boolean }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <header><h2>{title}</h2><button className="icon-button" onClick={onClose} aria-label="关闭"><X size={18} /></button></header>
        <div className="modal-body">{children}</div>
      </section>
    </div>
  )
}

/** 将 ISO 时间转换为简洁相对时间。 */
export function timeAgo(value: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(value).getTime()) / 1000)
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)} 天前`
  return new Date(value).toLocaleDateString('zh-CN')
}

/** 将未来 ISO 时间显示为明确的倒计时，已到期时提示后台即将领取。 */
export function timeUntil(value: string): string {
  const seconds = (new Date(value).getTime() - Date.now()) / 1000
  if (seconds <= 0) return '等待后台领取'
  if (seconds < 60) return `${Math.ceil(seconds)} 秒后`
  if (seconds < 3600) return `${Math.ceil(seconds / 60)} 分钟后`
  if (seconds < 86400) return `${Math.ceil(seconds / 3600)} 小时后`
  return new Date(value).toLocaleString('zh-CN')
}
