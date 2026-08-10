import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, FileAudio, FileImage, FileSpreadsheet, FileText, FileType2, Globe2, Link2, Plus, RefreshCw, Upload, Video } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Badge, EmptyState, Modal, Notice, PageHeader, timeAgo } from '../components/Ui'
import { ApiError, apiRequest, downloadAuthenticated } from '../lib/api'

interface SourceSummary {
  id: string
  sourceType: string
  title: string
  canonicalUri?: string
  status: string
  contentHash?: string
  jobId?: string
  jobStatus?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
  downloadAvailable: boolean
}

interface CreateResult { sourceId: string; jobId: string; status: string }

/** 多来源摄取页，网页/OCR/转写只由独立 Python 服务处理。 */
export default function SourcesPage() {
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [showAdd, setShowAdd] = useState(searchParams.get('add') === '1')
  const [refreshMessage, setRefreshMessage] = useState('')
  const sourceFilter = searchParams.get('search')?.trim().toLowerCase() ?? ''
  const sources = useQuery({
    queryKey: ['sources'],
    queryFn: () => apiRequest<SourceSummary[]>('/api/sources?limit=100'),
    refetchInterval: (query) => query.state.data?.some((source) => ['PENDING', 'PROCESSING'].includes(source.jobStatus ?? '')) ? 3000 : false,
  })
  const refreshMutation = useMutation({
    mutationFn: (id: string) => apiRequest<CreateResult>(`/api/sources/${id}/refresh`, { method: 'POST' }),
    onSuccess: async () => { setRefreshMessage('已确认重新提取，来源已进入处理队列。'); await queryClient.invalidateQueries({ queryKey: ['sources'] }) },
  })
  const visibleSources = (sources.data ?? []).filter((source) => !sourceFilter || `${source.title} ${source.canonicalUri ?? ''}`.toLowerCase().includes(sourceFilter))

  return (
    <div className="standard-page sources-page">
      <PageHeader title="资料来源" actions={<button className="button primary" onClick={() => setShowAdd(true)}><Plus size={16} />添加来源</button>} />
      {refreshMessage ? <Notice kind="success">{refreshMessage}</Notice> : null}
      {refreshMutation.error ? <Notice kind="error">{refreshMutation.error instanceof ApiError ? refreshMutation.error.message : '重新提取失败'}</Notice> : null}
      <section className="panel source-table-panel">
        <div className="panel-header"><div><h2>全部来源</h2><p>{sources.data?.length ?? 0} 份团队资料</p></div><button className="icon-button" onClick={() => { if (window.confirm('确认刷新资料来源列表吗？')) { setRefreshMessage('来源列表已刷新。'); sources.refetch() } }} aria-label="刷新"><RefreshCw size={16} /></button></div>
        <div className="source-list">
          {visibleSources.map((source) => <article key={source.id}><span className={`source-icon source-${source.sourceType.toLowerCase()}`}>{sourceIcon(source.sourceType, source.title)}</span><div className="source-main"><strong>{source.title}</strong><span>{source.canonicalUri ? <a href={source.canonicalUri} target="_blank" rel="noreferrer"><Link2 size={12} />{source.canonicalUri}</a> : source.contentHash ? `sha256:${source.contentHash.slice(0, 12)}…` : '等待提取'}</span></div><div className="source-kind">{sourceTypeLabel(source.sourceType)}</div><div><Badge value={source.jobStatus ?? source.status} />{source.errorMessage ? <small className="source-error" title={source.errorMessage}>{source.errorMessage}</small> : null}</div><time>{timeAgo(source.updatedAt)}</time><div className="source-actions">{source.downloadAvailable ? <button className="icon-button" onClick={() => downloadAuthenticated(`/api/sources/${source.id}/download`, source.title)} aria-label="下载原始文件"><Download size={15} /></button> : null}<button className="icon-button" onClick={() => { if (window.confirm(`确认重新提取“${source.title}”吗？这会重新生成候选知识。`)) { setRefreshMessage(''); refreshMutation.mutate(source.id) } }} disabled={refreshMutation.isPending} aria-label="重新提取"><RefreshCw size={15} /></button></div></article>)}
          {!sources.isLoading && !visibleSources.length ? <EmptyState icon={<Upload size={25} />} title={sourceFilter ? '没有匹配来源' : '还没有团队资料'} description={sourceFilter ? '尝试其他关键词。' : '添加文本、网页、文件、音频或视频，后台会开始编译。'} action={!sourceFilter ? <button className="button primary" onClick={() => setShowAdd(true)}>添加第一份来源</button> : undefined} /> : null}
        </div>
      </section>
      {showAdd ? <AddSourceModal onClose={() => setShowAdd(false)} /> : null}
    </div>
  )
}

/** 来源创建模态，JSON 与 multipart 两条通道共用成功反馈。 */
function AddSourceModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [tab, setTab] = useState<'TEXT' | 'URL' | 'FILE' | 'AUDIO' | 'VIDEO'>('TEXT')
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [uri, setUri] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const [success, setSuccess] = useState<CreateResult | null>(null)
  const mutation = useMutation({
    mutationFn: async () => {
      if (tab === 'TEXT' || tab === 'URL') return apiRequest<CreateResult>('/api/sources', { method: 'POST', body: JSON.stringify({ sourceType: tab, title, text: tab === 'TEXT' ? text : undefined, uri: tab === 'URL' ? uri : undefined }) })
      if (!file) throw new ApiError(400, 'FILE_REQUIRED', '请选择文件')
      const form = new FormData()
      form.append('file', file)
      form.append('title', title)
      form.append('sourceType', tab)
      return apiRequest<CreateResult>('/api/sources/upload', { method: 'POST', body: form })
    },
    onSuccess: async (value) => { setSuccess(value); await queryClient.invalidateQueries({ queryKey: ['sources'] }) },
  })

  if (success) return <Modal title="来源已进入编译队列" onClose={onClose}><div className="success-result"><span className="source-icon source-url"><RefreshCw size={26} /></span><h3>后台任务已创建</h3><p>任务 {success.jobId.slice(0, 8)}… 将在独立提取和 AI 编译后，根据权限直接发布新页面或送审。</p><button className="button primary" onClick={onClose}>查看处理状态</button></div></Modal>

  const tabs = [
    { key: 'TEXT', label: '文本', icon: <FileText /> }, { key: 'URL', label: '网页', icon: <Globe2 /> },
    { key: 'FILE', label: '文件 / OCR', icon: <Upload /> }, { key: 'AUDIO', label: '音频', icon: <FileAudio /> },
    { key: 'VIDEO', label: '视频', icon: <Video /> },
  ] as const

  /** 统一接收选择或拖入的文件，并用文件名补全空标题。 */
  function chooseFile(nextFile: File | null) {
    setFile(nextFile)
    if (nextFile && !title.trim()) setTitle(nextFile.name.replace(/\.[^.]+$/, ''))
  }

  const accept = tab === 'AUDIO' ? 'audio/*' : tab === 'VIDEO' ? 'video/*' : '.txt,.md,.markdown,.html,.htm,.docx,.pdf,.png,.jpg,.jpeg,.tif,.tiff,.bmp,.webp'
  return <Modal title="添加资料来源" onClose={onClose} wide><form className="source-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><div className="source-tabs">{tabs.map((item) => <button type="button" key={item.key} className={tab === item.key ? 'active' : ''} onClick={() => { setTab(item.key); setFile(null); setDragActive(false) }}>{item.icon}<span>{item.label}</span></button>)}</div><label>来源标题<input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="例如：数据保留政策 2026" required /></label>{tab === 'TEXT' ? <label>原始文本<textarea value={text} onChange={(event) => setText(event.target.value)} placeholder="粘贴会议纪要、制度、研究笔记…" required /></label> : null}{tab === 'URL' ? <label>网页地址<input type="url" value={uri} onChange={(event) => setUri(event.target.value)} placeholder="https://example.com/article" required /><small>默认拒绝私网和环回地址，防止 SSRF。</small></label> : null}{['FILE', 'AUDIO', 'VIDEO'].includes(tab) ? <label className={`file-drop ${dragActive ? 'dragging' : ''}`} onDragEnter={(event) => { event.preventDefault(); setDragActive(true) }} onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; setDragActive(true) }} onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragActive(false) }} onDrop={(event) => { event.preventDefault(); setDragActive(false); chooseFile(event.dataTransfer.files?.[0] ?? null) }}><Upload size={24} /><strong>{file?.name ?? '拖拽文件到这里，或点击选择'}</strong><small>{tab === 'FILE' ? '文本、Markdown、HTML、DOCX、PDF、图片 OCR' : '由独立转写服务处理'}</small><input type="file" accept={accept} onChange={(event) => chooseFile(event.target.files?.[0] ?? null)} required /></label> : null}{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '来源创建失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={mutation.isPending}>{mutation.isPending ? '正在上传…' : '进入编译队列'}</button></footer></form></Modal>
}

/** 来源类型图标。 */
function sourceIcon(type: string, title = '') {
  if (type === 'URL') return <Globe2 size={18} />
  if (type === 'AUDIO') return <FileAudio size={18} />
  if (type === 'VIDEO') return <Video size={18} />
  if (type === 'FILE') {
    const name = title.toLowerCase()
    if (/\.(png|jpe?g|gif|bmp|webp|tiff?)$/.test(name)) return <FileImage size={18} />
    if (/\.(xls|xlsx|csv)$/.test(name)) return <FileSpreadsheet size={18} />
    if (/\.(doc|docx|pdf|txt|md|html?)$/.test(name)) return <FileType2 size={18} />
    return <Upload size={18} />
  }
  return <FileText size={18} />
}

/** 将后端来源类型转换为中文。 */
function sourceTypeLabel(type: string) {
  return ({ TEXT: '文本', URL: '网页', FILE: '文件', AUDIO: '音频', VIDEO: '视频' } as Record<string, string>)[type] ?? type
}
