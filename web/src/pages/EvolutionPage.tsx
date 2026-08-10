import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, BrainCircuit, CheckCircle2, ChevronDown, ChevronUp, History, Play, RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Badge, EmptyState, Notice, PageHeader, timeAgo, timeUntil } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'

interface AutomationSettings { automaticUrlRefresh: boolean; refreshIntervalHours: number; maintenanceEnabled: boolean; optimizationIntervalMinutes: number; nextOptimizationAt: string; lastOptimizationAt?: string }
interface ReviewLink { id: string; title: string; status: string }
interface Contradiction { description: string; pageIds: string[]; evidence: string }
interface EvolutionRun { id: string; triggerType: string; status: string; modelProvider?: string; modelName?: string; pagesScanned: number; contradictionsFound: number; proposalsCreated: number; resultSummary?: string; errorMessage?: string; startedAt?: string; finishedAt?: string; createdAt: string; contradictions: Contradiction[]; reviews: ReviewLink[] }

/** 独立展示持续优化策略、运行状态、矛盾和审核提案，历史每 15 秒自动刷新。 */
export default function EvolutionPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { hasPermission } = useAuth()
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [page, setPage] = useState(1)
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null)
  const canRead = hasPermission('AUTOMATION_READ')
  const canManage = hasPermission('AUTOMATION_MANAGE')
  const settings = useQuery({ queryKey: ['automation-settings'], queryFn: () => apiRequest<AutomationSettings>('/api/automation/settings'), enabled: canRead })
  const runs = useQuery({ queryKey: ['evolution-runs'], queryFn: () => apiRequest<EvolutionRun[]>('/api/automation/runs?limit=100'), enabled: canRead, refetchInterval: 15_000 })
  const updateSettings = useMutation({ mutationFn: (value: AutomationSettings) => apiRequest<AutomationSettings>('/api/automation/settings', { method: 'PUT', body: JSON.stringify(value) }), onSuccess: (value) => queryClient.setQueryData(['automation-settings'], value) })
  const trigger = useMutation({ mutationFn: () => apiRequest<{ runId: string }>('/api/automation/runs', { method: 'POST' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['evolution-runs'] }) })
  const visibleRuns = useMemo(() => statusFilter === 'ALL' ? runs.data ?? [] : (runs.data ?? []).filter((run) => run.status === statusFilter), [runs.data, statusFilter])
  const pageSize = 6
  const pageCount = Math.max(1, Math.ceil(visibleRuns.length / pageSize))
  const pagedRuns = visibleRuns.slice((page - 1) * pageSize, page * pageSize)
  useEffect(() => setPage((value) => Math.min(value, pageCount)), [pageCount])
  const metrics = useMemo(() => summarizeRuns(runs.data ?? []), [runs.data])

  return <div className="standard-page evolution-page"><PageHeader title="持续优化记录" actions={<button className="icon-button" aria-label="刷新优化记录" onClick={() => runs.refetch()}><RefreshCw size={16} /></button>} />
    {settings.data ? <section className={`panel evolution-control ${settings.data.maintenanceEnabled ? 'enabled' : ''}`}>
      <div className="evolution-control-main"><span className="policy-icon"><BrainCircuit size={20} /></span><span><strong>AI 持续优化</strong><small>定期检查页面矛盾、过时表述和可合并知识；所有更新只生成审核单。</small></span></div>
      <label className="switch"><input aria-label="AI 持续优化" type="checkbox" checked={settings.data.maintenanceEnabled} disabled={!canManage || updateSettings.isPending} onChange={(event) => updateSettings.mutate({ ...settings.data!, maintenanceEnabled: event.target.checked })} /><i /></label>
      <label>运行间隔<select value={settings.data.optimizationIntervalMinutes} disabled={!canManage} onChange={(event) => updateSettings.mutate({ ...settings.data!, optimizationIntervalMinutes: Number(event.target.value) })}><option value={30}>每 30 分钟</option><option value={60}>每小时</option><option value={360}>每 6 小时</option><option value={1440}>每天</option></select></label>
      <div className="evolution-times"><span>上次：{settings.data.lastOptimizationAt ? timeAgo(settings.data.lastOptimizationAt) : '尚未运行'}</span><span>下次：{settings.data.maintenanceEnabled ? timeUntil(settings.data.nextOptimizationAt) : '已暂停'}</span></div>
      {canManage ? <button className="button secondary" disabled={trigger.isPending} onClick={() => trigger.mutate()}><Play size={15} />{trigger.isPending ? '正在排队…' : '立即运行一次'}</button> : null}
    </section> : null}
    {updateSettings.error ? <Notice kind="error">{updateSettings.error instanceof ApiError ? updateSettings.error.message : '持续优化设置保存失败'}</Notice> : null}
    {trigger.error ? <Notice kind="error">{trigger.error instanceof ApiError ? trigger.error.message : '持续优化任务创建失败'}</Notice> : null}
    <section className="panel automation-policy"><div><span className="policy-icon"><RefreshCw size={18} /></span><span><strong>持续刷新网页来源</strong><small>到期后自动重新提取与编译；已有知识更新仍进入审核。</small></span></div>{settings.data ? <><label className="switch"><input aria-label="持续刷新网页来源" type="checkbox" checked={settings.data.automaticUrlRefresh} disabled={!canManage} onChange={(event) => updateSettings.mutate({ ...settings.data!, automaticUrlRefresh: event.target.checked })} /><i /></label><label>刷新间隔<select value={settings.data.refreshIntervalHours} disabled={!canManage} onChange={(event) => updateSettings.mutate({ ...settings.data!, refreshIntervalHours: Number(event.target.value) })}><option value={6}>6 小时</option><option value={24}>24 小时</option><option value={72}>3 天</option><option value={168}>7 天</option></select></label></> : null}</section>

    <section className="evolution-summary"><div><strong>{metrics.total}</strong><span>总运行</span></div><div className="success"><strong>{metrics.succeeded}</strong><span>成功</span></div><div className="warning"><strong>{metrics.failed}</strong><span>失败</span></div><div><strong>{metrics.proposals}</strong><span>审核提案</span></div></section>

    <section className="panel evolution-history"><div className="panel-header"><div><h2><History size={18} />运行记录</h2><p>历史失败保留用于审计；点击“查看详情”展开矛盾和审核单。</p></div><div className="run-filters">{['ALL', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'RUNNING'].map((status) => <button key={status} className={statusFilter === status ? 'active' : ''} onClick={() => { setStatusFilter(status); setPage(1); setExpandedRunId(null) }}>{statusLabel(status)}</button>)}</div></div>
      <div className="run-list">{pagedRuns.map((run) => { const expanded = expandedRunId === run.id; return <article key={run.id} className={`run-card run-${run.status.toLowerCase()} ${expanded ? 'expanded' : ''}`}><header><span className="run-status-icon">{run.status === 'FAILED' ? <AlertTriangle /> : run.status === 'RUNNING' ? <span className="spinner" /> : <CheckCircle2 />}</span><div><strong>{run.triggerType === 'MANUAL' ? '手动优化' : '定时优化'} · {timeAgo(run.createdAt)}</strong><small>{run.modelName ? `${run.modelProvider} / ${run.modelName}` : '未调用模型'}</small></div><Badge value={run.status} /></header><p>{friendlyRunMessage(run)}</p><dl><div><dt>扫描页面</dt><dd>{run.pagesScanned}</dd></div><div><dt>发现矛盾</dt><dd>{run.contradictionsFound}</dd></div><div><dt>审核提案</dt><dd>{run.proposalsCreated}</dd></div></dl><button className="run-details-toggle" onClick={() => setExpandedRunId(expanded ? null : run.id)}>{expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}{expanded ? '收起详情' : `查看详情${run.contradictions.length + run.reviews.length ? ` · ${run.contradictions.length + run.reviews.length} 项` : ''}`}</button>{expanded ? <div className="run-details">{run.contradictions.length ? <div className="contradiction-list">{run.contradictions.map((item, index) => <div key={`${run.id}-${index}`}><strong>矛盾 {index + 1}</strong><span>{item.description}</span>{item.evidence ? <small>{item.evidence}</small> : null}</div>)}</div> : <span className="muted-box">本次没有发现矛盾。</span>}{run.reviews.length ? <footer>{run.reviews.map((review) => <button key={review.id} onClick={() => navigate(`/review/${review.id}`)}><span>{review.title}</span><Badge value={review.status} /></button>)}</footer> : null}</div> : null}</article> })}{!runs.isLoading && !visibleRuns.length ? <EmptyState icon={<History size={25} />} title="没有符合条件的记录" /> : null}</div>
      {visibleRuns.length > pageSize ? <div className="pagination"><button className="button ghost" disabled={page <= 1} onClick={() => setPage((value) => Math.max(1, value - 1))}>上一页</button><span>第 {page} / {pageCount} 页 · 共 {visibleRuns.length} 条</span><button className="button ghost" disabled={page >= pageCount} onClick={() => setPage((value) => Math.min(pageCount, value + 1))}>下一页</button></div> : null}
    </section>

  </div>
}

/** 汇总当前已加载运行记录，避免渲染阶段重复遍历。 */
function summarizeRuns(runs: EvolutionRun[]) {
  return runs.reduce((summary, run) => ({ total: summary.total + 1, succeeded: summary.succeeded + (run.status === 'SUCCEEDED' ? 1 : 0), failed: summary.failed + (run.status === 'FAILED' ? 1 : 0), proposals: summary.proposals + run.proposalsCreated }), { total: 0, succeeded: 0, failed: 0, proposals: 0 })
}

/** 把历史英文底层异常转换为可执行的中文恢复提示，新记录由后端直接返回同样语义。 */
function friendlyRunMessage(run: EvolutionRun) {
  const message = run.errorMessage || run.resultSummary || '等待后台处理…'
  if (message.includes('Unable to decrypt model credential') || message.includes('Model encryption key is unavailable')) return '模型接口密钥已失效，持续优化已自动暂停；请到“模型与 MCP”重新输入密钥并测试保存。'
  if (message.includes('额度') || message.includes('使用上限') || message.includes('429')) return `模型额度已达到服务商限制，持续优化已自动暂停；${message}`
  return message
}

/** 将运行状态筛选转换为中文。 */
function statusLabel(status: string) {
  return ({ ALL: '全部', SUCCEEDED: '成功', FAILED: '失败', SKIPPED: '已跳过', RUNNING: '运行中' } as Record<string, string>)[status] ?? status
}
