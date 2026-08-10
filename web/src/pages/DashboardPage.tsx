import { useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpen, CheckCircle2, Clock3, FileCheck2, ShieldCheck, TrendingUp } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Badge, LoadingBlock, PageHeader, timeAgo } from '../components/Ui'
import { apiRequest } from '../lib/api'

interface DashboardData {
  metrics: { publishedPages: number; pendingReviews: number; activeSources: number; readySources: number }
  pendingReviews: Array<{ id: string; title: string; status: string; sourceTitle: string; proposedBy: string; actionCount: number; createdAt: string }>
  recentPages: Array<{ id: string; slug: string; title: string; pageType: string; revisionNo: number; hasEvidence: boolean; updatedAt: string }>
  recentActivity: Array<{ id: string; action: string; resourceType: string; resourceId?: string; actor: string; createdAt: string }>
  sourceBreakdown: Array<{ sourceType: string; total: number; ready: number }>
}

const activityLabels: Record<string, string> = {
  SOURCE_CREATED: '添加了新资料来源',
  SOURCE_UPLOADED: '上传了新文件',
  SOURCE_COMPILED: 'AI 完成了一次知识编译',
  CHANGE_SET_SUBMITTED: '提交了知识变更',
  CHANGE_SET_APPROVED: '审核并发布了知识变更',
  CHANGE_SET_REJECTED: '驳回了知识变更',
  PAGE_CREATED: '发布了新知识页面',
  MEMBER_ADDED: '添加了空间成员',
  SOURCE_REFRESHED: '重新提取了资料来源',
  SOURCE_READY: '资料来源处理完成',
  INGESTION_STARTED: '开始处理资料来源',
  INGESTION_COMPLETED: '完成资料来源处理',
  WIKI_PAGE_UPDATED: '更新了知识页面',
  WIKI_PAGE_CREATED: '创建了知识页面',
  REVIEW_APPROVED: '通过了知识审核',
  REVIEW_REJECTED: '驳回了知识审核',
}

/** 将后台审计动作统一转换为中文，未知动作也不直接暴露英文枚举。 */
function activityLabel(action: string) {
  return activityLabels[action] ?? (action.includes('SOURCE') ? '更新了资料来源' : action.includes('CHANGE_SET') ? '处理了知识变更' : action.includes('PAGE') ? '更新了知识页面' : action.includes('MEMBER') ? '调整了空间成员' : '产生了一条系统活动')
}

/** 概念稿落地的企业工作台，单次聚合请求避免卡片数据瀑布。 */
export default function DashboardPage() {
  const { user, hasPermission } = useAuth()
  const navigate = useNavigate()
  const dashboard = useQuery({ queryKey: ['dashboard', user?.workspaceId], queryFn: () => apiRequest<DashboardData>('/api/dashboard') })
  const data = dashboard.data

  return (
    <div className="dashboard-page">
      <PageHeader
        title={`早上好，${user?.displayName}`}
        actions={undefined}
      />

      {dashboard.isLoading ? <LoadingBlock rows={8} /> : null}
      {data ? (
        <>
          <section className="metric-grid">
            <MetricCard icon={<BookOpen />} value={data.metrics.publishedPages} label="已发布页面" tone="blue" onClick={() => navigate('/knowledge')} />
            <MetricCard icon={<Clock3 />} value={data.metrics.pendingReviews} label="待审核变更" tone="amber" alert={data.metrics.pendingReviews > 0} onClick={() => navigate('/review')} />
            <MetricCard icon={<FileCheck2 />} value={data.metrics.activeSources} label="活动来源" tone="green" />
          </section>

          <section className="dashboard-middle">
            <div className="panel review-panel">
              <div className="panel-header"><div><h2>审核队列</h2></div>{hasPermission('REVIEW_READ') ? <button className="text-button" onClick={() => navigate('/review')}>查看全部 <ArrowRight size={15} /></button> : null}</div>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>候选变更</th><th>审核状态</th><th>来源</th><th>提交人</th><th>动作</th><th>等待</th></tr></thead>
                  <tbody>
                    {data.pendingReviews.map((review) => (
                      <tr key={review.id} onClick={() => hasPermission('REVIEW_READ') && navigate(`/review/${review.id}`)}>
                        <td><div className="title-cell"><FileCheck2 size={17} /><span><strong>{review.title}</strong></span></div></td>
                        <td><Badge value={review.status} /></td><td>{review.sourceTitle}</td>
                        <td>{review.proposedBy}</td><td>{review.actionCount} 项</td><td>{timeAgo(review.createdAt)}</td>
                      </tr>
                    ))}
                    {!data.pendingReviews.length ? <tr><td colSpan={6}><div className="table-empty"><CheckCircle2 size={19} />所有变更都已处理</div></td></tr> : null}
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <section className="dashboard-bottom">
            <div className="panel activity-panel"><div className="panel-header"><h2>最近 AI 与成员活动</h2></div><div className="activity-list">{data.recentActivity.map((activity) => <div key={activity.id}><span className="activity-icon"><TrendingUp size={14} /></span><span><strong>{activityLabel(activity.action)}</strong><small>{activity.actor} · {timeAgo(activity.createdAt)}</small></span></div>)}{!data.recentActivity.length ? <p className="muted">暂无活动</p> : null}</div></div>
            <div className="panel recent-panel"><div className="panel-header"><h2>最近更新的知识</h2><button className="text-button" onClick={() => navigate('/knowledge')}>全部页面 <ArrowRight size={15} /></button></div><div className="recent-table">{data.recentPages.map((page) => <button key={page.id} onClick={() => navigate(`/knowledge/${page.id}`)}><span><strong>{page.title}</strong><small>{pageTypeLabel(page.pageType)}</small></span><span className={page.hasEvidence ? 'evidence-ok' : 'evidence-missing'}>{page.hasEvidence ? <><ShieldCheck size={14} />有证据</> : '基础页'}</span><time>{timeAgo(page.updatedAt)}</time></button>)}</div></div>
          </section>
        </>
      ) : null}
    </div>
  )
}

/** 顶部指标卡。 */
function MetricCard({ icon, value, label, tone, alert, onClick }: { icon: React.ReactNode; value: string | number; label: string; tone: string; alert?: boolean; onClick?: () => void }) {
  return <button type="button" className="metric-card" onClick={onClick}><span className={`metric-icon ${tone}`}>{icon}</span><div><strong>{value}</strong><span>{label}</span></div>{alert ? <i className="metric-alert" aria-label="有待处理事项" /> : null}</button>
}

/** 将页面类型转换为中文。 */
function pageTypeLabel(type: string) {
  return ({ TOPIC: '主题', ENTITY: '实体', SYNTHESIS: '综合', CONTEXT: '上下文', README: '说明' } as Record<string, string>)[type] ?? type
}
