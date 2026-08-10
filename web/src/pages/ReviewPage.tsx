import { diffLines } from 'diff'
import { Check, ChevronRight, FileCheck2, MessageSquareText, ShieldAlert, ShieldCheck, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Badge, EmptyState, LoadingBlock, Notice, PageHeader, timeAgo } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'
import type { ReviewDetail, ReviewSummary } from '../types'

interface ReviewResult { status: string; changeSetId: string; pageIds: string[]; message: string }

/** 审核中心：逐项展示基线/候选差异，并以原子事务批准或驳回。 */
export default function ReviewPage() {
  const { changeSetId } = useParams()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [comment, setComment] = useState('')
  const [selectedAction, setSelectedAction] = useState(0)
  const [decisionResult, setDecisionResult] = useState<ReviewResult | null>(null)
  const pageFilter = searchParams.get('pageId')
  const queryChangeSetId = searchParams.get('changeSetId')
  const reviews = useQuery({ queryKey: ['reviews', pageFilter], queryFn: () => apiRequest<ReviewSummary[]>(`/api/reviews?limit=100${pageFilter ? `&pageId=${pageFilter}` : ''}`) })
  const selectedId = changeSetId ?? queryChangeSetId ?? reviews.data?.[0]?.id
  const detail = useQuery({ queryKey: ['review', selectedId], queryFn: () => apiRequest<ReviewDetail>(`/api/reviews/${selectedId}`), enabled: Boolean(selectedId) })
  const decision = useMutation({
    mutationFn: ({ type }: { type: 'approve' | 'reject' }) => apiRequest<ReviewResult>(`/api/reviews/${selectedId}/${type}`, { method: 'POST', body: JSON.stringify({ comment }) }),
    onSuccess: async (result) => {
      setDecisionResult(result)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['reviews'] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['pages'] }),
      ])
    },
  })
  const action = detail.data?.actions[selectedAction]

  /** 驳回需要明确理由，批准意见可选。 */
  function decide(type: 'approve' | 'reject') {
    if (type === 'reject' && !comment.trim()) return
    decision.mutate({ type })
  }

  return (
    <div className="review-page">
      <PageHeader title="审核中心" />
      <div className="review-layout">
        <aside className="review-queue">
          <div className="queue-heading"><h2>待处理</h2><span>{reviews.data?.length ?? 0}</span></div>
          <div className="queue-list">{reviews.data?.map((review) => <button key={review.id} className={review.id === selectedId ? 'selected' : ''} onClick={() => { setSelectedAction(0); setDecisionResult(null); navigate(`/review/${review.id}`) }}><span className="status-pin" /><span><strong>{review.title}</strong><small>{review.proposedBy} · {timeAgo(review.createdAt)}</small><em><Badge value={review.status} /><Badge value={review.sourceTitle} /></em></span><ChevronRight size={15} /></button>)}</div>
          {!reviews.isLoading && !reviews.data?.length ? <EmptyState icon={<ShieldCheck size={25} />} title="审核队列已清空" description="新的 AI 或成员提案会出现在这里。" /> : null}
        </aside>

        <section className="review-workspace">
          {detail.isLoading ? <LoadingBlock rows={10} /> : null}
          {detail.data && !decisionResult ? <>
            <header className="review-detail-header"><div><div className="reader-meta"><Badge value={detail.data.status} /><Badge value={detail.data.sourceTitle} /><span>{detail.data.proposedBy} 提交</span></div><h2>{detail.data.title}</h2><p>{detail.data.summary}</p></div></header>
            <Notice kind="info">更新内容将在审核通过后生效。</Notice>
            <div className="action-tabs">{detail.data.actions.map((item, index) => <button key={item.id} className={index === selectedAction ? 'active' : ''} onClick={() => setSelectedAction(index)}><FileCheck2 size={14} />{item.proposedTitle}</button>)}</div>
            {action ? <DiffViewer before={action.baseContentMarkdown} after={action.proposedContentMarkdown} actionType={action.actionType} /> : null}
            <footer className="review-decision"><label><MessageSquareText size={17} /><textarea value={comment} onChange={(event) => setComment(event.target.value)} placeholder="填写审核意见；驳回时必须说明原因…" /></label>{decision.error ? <div className="form-error">{decision.error instanceof ApiError ? decision.error.message : '审核操作失败'}</div> : null}<div><button className="button danger-soft" onClick={() => decide('reject')} disabled={decision.isPending || !comment.trim()}><X size={16} />驳回</button><button className="button primary" onClick={() => decide('approve')} disabled={decision.isPending}><Check size={16} />批准并发布</button></div></footer>
          </> : null}
          {decisionResult ? <div className="decision-result">{decisionResult.status === 'APPROVED' ? <ShieldCheck size={42} /> : decisionResult.status === 'SUPERSEDED' ? <ShieldAlert size={42} /> : <X size={42} />}<h2>{decisionResult.message}</h2><p>{decisionResult.status === 'SUPERSEDED' ? '目标页在提案后已产生新修订，系统没有覆盖它。' : `审核状态：${decisionResult.status}`}</p><button className="button primary" onClick={() => { setDecisionResult(null); setComment(''); navigate('/review') }}>处理下一项</button></div> : null}
        </section>
      </div>
    </div>
  )
}

/** 按行展示 Markdown 差异，新增和删除使用稳定语义色。 */
function DiffViewer({ before, after, actionType }: { before: string; after: string; actionType: string }) {
  const changes = useMemo(() => diffLines(before ?? '', after ?? ''), [before, after])
  return <div className="diff-view"><div className="diff-toolbar"><span><i className="diff-dot removed" />删除</span><span><i className="diff-dot added" />新增</span><strong>{actionType === 'CREATE_PAGE' ? '全新页面' : 'Markdown 行差异'}</strong></div><div className="diff-content">{changes.map((change, index) => <pre key={index} className={change.added ? 'added' : change.removed ? 'removed' : 'unchanged'}><span>{change.added ? '+' : change.removed ? '−' : ' '}</span>{change.value}</pre>)}</div></div>
}
