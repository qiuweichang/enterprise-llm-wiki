import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { apiRequest } from '../lib/api'
import { Badge, Modal } from './Ui'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { PageDetail } from '../types'

interface SemanticStatus { model: string; dimensions: number; storage: string; index: { enabled: boolean; minScore: number; total: number; ready: number; failed: number } }
interface IndexJob { pageId: string; title: string; status: string; attempts: number; lastError: string; chunks: number }
interface SearchTest { success: boolean; durationMs: number; dimensions: number; pages: { id: string; title: string }[] }

/** 空间级语义检索运维面板；进度独立轮询，操作由后端校验权限和真实模型可用性。 */
export function SemanticPanel() {
  const { user } = useAuth()
  const cache = useQueryClient()
  const key = ['semantic', user?.workspaceId]
  const status = useQuery({ queryKey: key, queryFn: () => apiRequest<SemanticStatus>('/api/semantic'), refetchInterval: 5000 })
  const [showJobs, setShowJobs] = useState(false)
  const [confirmRebuild, setConfirmRebuild] = useState(false)
  const [question, setQuestion] = useState('哪家公司的模型适合程序开发？')
  const [preview, setPreview] = useState<string | null>(null)
  const document = useQuery({ queryKey: ['semantic-preview', user?.workspaceId, preview], queryFn: () => apiRequest<PageDetail>(`/api/pages/${preview}`), enabled: Boolean(preview) })
  const jobs = useQuery({ queryKey: [...key, 'jobs'], queryFn: () => apiRequest<IndexJob[]>('/api/semantic/jobs'), enabled: showJobs, refetchInterval: showJobs ? 5000 : false })
  const toggle = useMutation({ mutationFn: () => apiRequest<SemanticStatus>('/api/semantic', { method: 'PUT', body: JSON.stringify({ enabled: !status.data?.index.enabled, minScore: status.data?.index.minScore ?? 0.5 }) }), onSuccess: () => cache.invalidateQueries({ queryKey: key }) })
  const rebuild = useMutation({ mutationFn: () => apiRequest('/api/semantic/rebuild', { method: 'POST' }), onSuccess: () => { setConfirmRebuild(false); cache.invalidateQueries({ queryKey: key }) } })
  const test = useMutation({ mutationFn: () => apiRequest<SearchTest>('/api/semantic/test', { method: 'POST', body: JSON.stringify({ question }) }) })
  const data = status.data
  const error = status.error ?? toggle.error ?? rebuild.error ?? test.error
  return <section className="panel semantic-panel">
    <div className="panel-header"><h2>语义检索</h2><div className="semantic-actions">
      <button className="button secondary" onClick={() => setShowJobs(true)}>索引记录</button>
      <button className="button secondary" onClick={() => setConfirmRebuild(true)}>重建索引</button>
      <button className="button primary" disabled={!data || toggle.isPending} onClick={() => toggle.mutate()}>{toggle.isPending ? '检测中…' : data?.index.enabled ? '关闭语义检索' : '启用语义检索'}</button>
    </div></div>
    <div className="semantic-summary"><span>本地中文 BGE · {data?.dimensions ?? 512} 维</span><span>已索引 {data?.index.ready ?? 0} / {data?.index.total ?? 0} 页</span><span>失败 {data?.index.failed ?? 0} 页</span><Badge value={data?.index.enabled ? 'ENABLED' : 'DISABLED'} /></div>
    <form className="semantic-test" onSubmit={event => { event.preventDefault(); test.mutate() }}>
      <input aria-label="语义检索测试问题" value={question} maxLength={320} onChange={event => { setQuestion(event.target.value); test.reset() }} />
      <button className="button secondary" disabled={!question.trim() || test.isPending}>{test.isPending ? '检索中…' : '测试语义检索'}</button>
    </form>
    {test.data ? <div className="semantic-results"><span>模型调用成功 · {test.data.durationMs} 毫秒 · {test.data.pages.length} 个混合检索结果</span><div>{test.data.pages.map(page => <button className="button ghost" key={page.id} onClick={() => setPreview(page.id)}>{page.title}</button>)}</div>{!test.data.pages.length ? <span>没有匹配页面；请确认索引已完成。</span> : null}</div> : null}
    {error ? <div className="form-error">{error.message}</div> : null}
    {confirmRebuild ? <Modal title="重建语义索引" onClose={() => setConfirmRebuild(false)}><p>将重新向量化当前空间全部已发布页面。重建期间暂用关键词检索，不改变知识正文。</p><button className="button primary" disabled={rebuild.isPending} onClick={() => rebuild.mutate()}>确认重建</button></Modal> : null}
    {showJobs ? <Modal title="语义索引记录（最近 50 条）" onClose={() => setShowJobs(false)} wide><div className="semantic-jobs">{jobs.error ? <div className="form-error">{jobs.error.message}</div> : null}{jobs.data?.map(job => <article key={job.pageId}><strong>{job.title}</strong><Badge value={job.status === 'DONE' ? 'SUCCEEDED' : job.status} /><span>{job.chunks} 块 · 尝试 {job.attempts} 次</span>{job.lastError ? <p className="form-error">{job.lastError}</p> : null}</article>)}</div></Modal> : null}
    {preview ? <Modal title={document.data?.title ?? '文档预览'} onClose={() => setPreview(null)} wide><div className="markdown-body">{document.error ? document.error.message : <ReactMarkdown remarkPlugins={[remarkGfm]}>{document.data?.contentMarkdown ?? '加载中…'}</ReactMarkdown>}</div></Modal> : null}
  </section>
}
