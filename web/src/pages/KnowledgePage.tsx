import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Boxes, BookOpen, ChevronRight, CircleDot, FilePenLine, FileText, History as HistoryIcon, Link2, Merge, Plus, Search, ShieldCheck, Sparkles, Trash2 } from 'lucide-react'
import { useDeferredValue, useEffect, useMemo, useState } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { useNavigate, useParams } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import { useAuth } from '../auth/AuthContext'
import { Badge, EmptyState, LoadingBlock, Modal, Notice, timeAgo } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'
import type { PageDetail, PageSummary } from '../types'

interface MutationResult { status: string; pageId?: string; changeSetId?: string; message: string }

/** Markdown 标题整体下移一级，保证当前知识页只有阅读区标题承担 h1 语义。 */
const wikiMarkdownComponents: Components = {
  h1: ({ node: _node, ...props }) => <h2 {...props} />,
  h2: ({ node: _node, ...props }) => <h3 {...props} />,
  h3: ({ node: _node, ...props }) => <h4 {...props} />,
}

/** 三栏企业知识工作台：页面树、Markdown 正文、来源证据与反向链接。 */
export default function KnowledgePage() {
  const { pageId } = useParams()
  const navigate = useNavigate()
  const { hasPermission } = useAuth()
  const queryClient = useQueryClient()
  const [filter, setFilter] = useState('')
  const deferredFilter = useDeferredValue(filter)
  const [editor, setEditor] = useState<'new' | 'edit' | null>(null)
  const [actionMessage, setActionMessage] = useState('')
  const [listPage, setListPage] = useState(1)
  const [historyItem, setHistoryItem] = useState<PageDetail['history'][number] | null>(null)
  const pages = useQuery({ queryKey: ['pages'], queryFn: () => apiRequest<PageSummary[]>('/api/pages?limit=100') })
  const selectedId = pageId ?? pages.data?.[0]?.id
  const detail = useQuery({
    queryKey: ['page', selectedId],
    queryFn: () => apiRequest<PageDetail>(`/api/pages/${selectedId}`),
    enabled: Boolean(selectedId),
  })
  const archive = useMutation({ mutationFn: () => apiRequest<MutationResult>(`/api/pages/${selectedId}/archive`, { method: 'POST' }), onSuccess: async (value) => { setActionMessage(value.message); await queryClient.invalidateQueries({ queryKey: ['pages'] }) } })
  const removeRelation = useMutation({ mutationFn: (relatedPageId: string) => apiRequest<MutationResult>(`/api/pages/${selectedId}/relations/${relatedPageId}/remove`, { method: 'POST' }), onSuccess: async (value) => { setActionMessage(value.message); await queryClient.invalidateQueries({ queryKey: ['page', selectedId] }) } })
  const filtered = useMemo(() => {
    const query = deferredFilter.trim().toLowerCase()
    return query ? pages.data?.filter((page) => `${page.title} ${page.excerpt}`.toLowerCase().includes(query)) ?? [] : pages.data ?? []
  }, [pages.data, deferredFilter])
  const pageSize = 8
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize))
  const visiblePages = filtered.slice((listPage - 1) * pageSize, listPage * pageSize)
  useEffect(() => setListPage(1), [deferredFilter])
  useEffect(() => setListPage((value) => Math.min(value, pageCount)), [pageCount])

  return (
    <div className="knowledge-workbench">
      <aside className="knowledge-list">
        <div className="knowledge-list-header"><div><h2>知识库</h2></div>{hasPermission('PAGE_CREATE') ? <button className="icon-button primary-soft" onClick={() => setEditor('new')} aria-label="新建页面"><Plus size={17} /></button> : null}</div>
        <label className="list-search"><Search size={15} /><input value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="搜索已发布页面" /></label>
        <div className="page-type-summary"><span>{pages.data?.length ?? 0} 页</span></div>
        <div className="page-list-scroll">
          {visiblePages.map((page) => <button key={page.id} className={page.id === selectedId ? 'selected' : ''} onClick={() => navigate(`/knowledge/${page.id}`)}><span className={`page-type-icon type-${page.pageType.toLowerCase()}`}>{pageTypeIcon(page.pageType)}</span><span><strong>{page.title}</strong><small>{pageTypeLabel(page.pageType)} · {timeAgo(page.updatedAt)}</small></span><ChevronRight size={15} /></button>)}
          {!pages.isLoading && !filtered.length ? <EmptyState title="没有匹配页面" description="尝试其他关键词，或创建第一页知识。" /> : null}
        </div>
        {filtered.length > pageSize ? <div className="page-pagination"><button disabled={listPage <= 1} onClick={() => setListPage((value) => Math.max(1, value - 1))}>上一页</button><span>{listPage} / {pageCount}</span><button disabled={listPage >= pageCount} onClick={() => setListPage((value) => Math.min(pageCount, value + 1))}>下一页</button></div> : null}
      </aside>

      <article className="wiki-reader">
        {detail.isLoading ? <LoadingBlock rows={9} /> : null}
        {detail.data ? (
          <>
            <header className="wiki-reader-header"><div><div className="reader-meta"><Badge value={detail.data.pageType} /><span>{timeAgo(detail.data.updatedAt)} 更新</span></div><h1>{detail.data.title}</h1></div><div className="reader-actions">{hasPermission('PAGE_CREATE') ? <button className="button secondary" onClick={() => setEditor('edit')}><FilePenLine size={15} />修改</button> : null}{hasPermission('PAGE_UPDATE_PROPOSE') ? <button className="button danger" disabled={archive.isPending} onClick={() => { if (window.confirm(`确定提交删除“${detail.data.title}”的审核吗？审核通过后页面会归档。`)) archive.mutate() }}><Trash2 size={15} />删除</button> : null}</div></header>
            {actionMessage ? <Notice kind="success"><ShieldCheck size={16} />{actionMessage}</Notice> : null}{archive.error ? <Notice kind="error">{archive.error instanceof ApiError ? archive.error.message : '删除提案提交失败'}</Notice> : null}
            <div className="knowledge-content"><div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]} components={wikiMarkdownComponents}>{detail.data.contentMarkdown}</ReactMarkdown></div></div>
          </>
        ) : selectedId ? null : <EmptyState icon={<Sparkles size={25} />} title="知识库还没有页面" description="添加来源后，AI 会生成可审核的知识页面。" action={hasPermission('SOURCE_CREATE') ? <button className="button primary" onClick={() => navigate('/sources?add=1')}>添加第一份来源</button> : undefined} />}
      </article>

      <aside className="evidence-panel">
        <div className="evidence-heading"><h2>证据与关联</h2></div>
        {detail.data ? <>
          <details className="evidence-section" open><summary className="evidence-section-heading"><ShieldCheck size={16} /><h3>来源证据</h3></summary>
            <div className="evidence-list">{detail.data.evidence.map((item) => <article key={item.id}><div><span className="source-type">{sourceTypeLabel(item.sourceType)}</span><strong>{item.sourceTitle}</strong></div><blockquote>{item.quote || '来源已关联，暂无摘录。'}</blockquote><footer>{item.canonicalUri ? <a href={item.canonicalUri} target="_blank" rel="noreferrer">打开来源 <Link2 size={12} /></a> : null}</footer></article>)}{!detail.data.evidence.length ? <p className="muted-box">这是基础或手工页面，当前修订没有外部来源证据。</p> : null}</div>
          </details>
          <details className="evidence-section" open><summary className="evidence-section-heading"><Link2 size={16} /><h3>反向链接</h3></summary><div className="backlink-list">{detail.data.backlinks.map((link) => <button key={link.pageId} onClick={() => navigate(`/knowledge/${link.pageId}`)}><span><strong>{link.title}</strong><small>{relationTypeLabel(link.relationType)}</small></span><ChevronRight size={14} /></button>)}{!detail.data.backlinks.length ? <p className="muted-box">还没有其他页面链接到这里。</p> : null}</div></details>
          <details className="evidence-section" open><summary className="evidence-section-heading"><Link2 size={16} /><h3>关联知识</h3></summary><div className="backlink-list">{uniqueRelations(detail.data.relatedPages).map((link) => <div className="related-page-row" key={`${link.pageId}-${link.relationType}`}><button onClick={() => navigate(`/knowledge/${link.pageId}`)}><span><strong>{link.title}</strong><small>{relationTypeLabel(link.relationType)}</small></span><ChevronRight size={14} /></button>{hasPermission('PAGE_UPDATE_PROPOSE') ? <button className="icon-button danger" disabled={removeRelation.isPending} onClick={() => { if (window.confirm(`确定提交删除与“${link.title}”关联的审核吗？`)) removeRelation.mutate(link.pageId) }} aria-label={`删除关联知识 ${link.title}`}><Trash2 size={14} /></button> : null}</div>)}{!detail.data.relatedPages.length ? <p className="muted-box">当前页面没有主动关联其他知识。</p> : null}{removeRelation.error ? <p className="form-error">{removeRelation.error instanceof ApiError ? removeRelation.error.message : '关联删除提案提交失败'}</p> : null}</div></details>
          <section className="evidence-section history-section"><div className="evidence-section-heading"><HistoryIcon size={16} /><h3>审核与修改记录</h3></div><div className="history-list">{detail.data.history.map((item) => <button key={item.changeSetId} onClick={() => setHistoryItem(item)}><span><strong>{item.title}</strong><small>{historyStatusLabel(item.status)} · {timeAgo(item.createdAt)}</small></span><ChevronRight size={14} /></button>)}{!detail.data.history.length ? <p className="muted-box">暂无审核或修改记录。</p> : null}</div>{detail.data.history.length ? <button className="text-button full" onClick={() => navigate(`/review?pageId=${detail.data.id}`)}>前往审核中心 <ChevronRight size={13} /></button> : null}</section>
        </> : null}
      </aside>

      {editor ? <PageEditor mode={editor} page={editor === 'edit' ? detail.data : undefined} onClose={() => setEditor(null)} /> : null}
      {historyItem ? <Modal title="审核与修改记录" onClose={() => setHistoryItem(null)}><div className="history-modal"><div className="reader-meta"><Badge value={historyItem.status} /><Badge value={historyItem.changeType} /><span>{timeAgo(historyItem.createdAt)}</span></div><h3>{historyItem.title}</h3><p>{historyItem.summary || '未填写变更说明。'}</p><dl><div><dt>提交人</dt><dd>{historyItem.proposedBy}</dd></div><div><dt>审核人</dt><dd>{historyItem.reviewer ?? '尚未审核'}</dd></div><div><dt>审核结果</dt><dd>{historyItem.decision ? historyDecisionLabel(historyItem.decision) : '待处理'}</dd></div></dl>{historyItem.comment ? <blockquote>{historyItem.comment}</blockquote> : null}<footer><button className="button secondary" onClick={() => { setHistoryItem(null); navigate(`/review?changeSetId=${historyItem.changeSetId}`) }}>前往审核中心</button><button className="button primary" onClick={() => setHistoryItem(null)}>关闭</button></footer></div></Modal> : null}
    </div>
  )
}

/** 双向 WikiLink 可能由后端同时作为入边和出边返回，详情区只展示一次相同页面关系。 */
function uniqueRelations<T extends { pageId: string; relationType: string }>(relations: T[]) {
  return relations.filter((relation, index) => relations.findIndex((candidate) =>
    candidate.pageId === relation.pageId && candidate.relationType === relation.relationType) === index)
}

/** 新建与更新共用的 Markdown 编辑器；提交后展示直接发布或待审核结果。 */
function PageEditor({ mode, page, onClose }: { mode: 'new' | 'edit'; page?: PageDetail; onClose: () => void }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [title, setTitle] = useState(page?.title ?? '')
  const [pageType, setPageType] = useState(page?.pageType ?? 'TOPIC')
  const [content, setContent] = useState(page?.contentMarkdown ?? '# ')
  const [result, setResult] = useState<MutationResult | null>(null)
  const mutation = useMutation({
    mutationFn: () => apiRequest<MutationResult>('/api/pages', { method: 'POST', body: JSON.stringify({ pageId: page?.id, title, pageType, contentMarkdown: content }) }),
    onSuccess: async (value) => {
      setResult(value)
      await queryClient.invalidateQueries({ queryKey: ['pages'] })
      if (value.pageId) await queryClient.invalidateQueries({ queryKey: ['page', value.pageId] })
    },
  })

  if (result) return <Modal title="提交完成" onClose={onClose}><div className="success-result"><ShieldCheck size={32} /><h3>{result.message}</h3><p>{result.status === 'PENDING_REVIEW' ? '审核通过前，已发布页面不会发生变化。' : '新页面已成为当前空间的已发布知识。'}</p><div>{result.changeSetId ? <button className="button primary" onClick={() => navigate(`/review/${result.changeSetId}`)}>查看审核单</button> : null}<button className="button secondary" onClick={onClose}>完成</button></div></div></Modal>

  return <Modal title={mode === 'new' ? '新建知识页面' : '修改知识页面'} onClose={onClose} wide><form className="editor-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><div className="editor-fields"><label>页面标题<input value={title} onChange={(event) => setTitle(event.target.value)} required /></label><label>页面类型<select value={pageType} onChange={(event) => setPageType(event.target.value)}><option value="TOPIC">主题</option><option value="ENTITY">实体</option><option value="SYNTHESIS">综合</option><option value="CONTEXT">上下文</option><option value="README">说明</option></select></label></div><label>Markdown 正文<textarea className="markdown-editor" value={content} onChange={(event) => setContent(event.target.value)} required /></label>{mode === 'edit' ? <Notice kind="warning">已有知识的更新一律提交审核，任何角色都不能从这里绕过。</Notice> : null}{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '提交失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={mutation.isPending}>{mutation.isPending ? '正在提交…' : mode === 'edit' ? '提交审核' : '创建页面'}</button></footer></form></Modal>
}

/** 将知识页面类型转换为中文。 */
function pageTypeLabel(type: string) {
  return ({ TOPIC: '主题', ENTITY: '实体', SYNTHESIS: '综合', CONTEXT: '上下文', README: '说明' } as Record<string, string>)[type] ?? type
}

/** 使用形状区分页面语义，避免实体、主题和上下文在列表中混淆。 */
function pageTypeIcon(type: string) {
  if (type === 'ENTITY') return <CircleDot size={15} />
  if (type === 'SYNTHESIS') return <Merge size={15} />
  if (type === 'CONTEXT') return <Boxes size={15} />
  if (type === 'README') return <FileText size={15} />
  return <BookOpen size={15} />
}

/** 将页面关系类型转换为中文。 */
function relationTypeLabel(type: string) {
  return ({ WIKI_LINK: 'Wiki 链接', RELATED: '相关', PARENT: '上级', CHILD: '下级' } as Record<string, string>)[type] ?? type
}

/** 将证据来源类型转换为中文。 */
function sourceTypeLabel(type: string) {
  return ({ TEXT: '文本', URL: '网页', FILE: '文件', AUDIO: '音频', VIDEO: '视频' } as Record<string, string>)[type] ?? type
}

/** 将审核状态转换成面向成员的中文状态。 */
function historyStatusLabel(status: string) {
  return ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', SUPERSEDED: '已失效', FAILED: '失败' } as Record<string, string>)[status] ?? status
}

/** 将审核决策转换为中文。 */
function historyDecisionLabel(decision: string) {
  return decision === 'APPROVE' ? '通过' : decision === 'REJECT' ? '驳回' : decision
}
