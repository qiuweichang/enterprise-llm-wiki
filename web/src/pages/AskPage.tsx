import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Copy, MessageSquarePlus, Pencil, Pin, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import { useNavigate, useSearchParams } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import { Badge, EmptyState, LoadingBlock, Modal, Notice, PageHeader, timeAgo } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'
import type { PageDetail } from '../types'

interface Citation { number: number; pageId: string; slug: string; title: string; revisionNo: number; excerpt: string }
interface ConversationSummary { id: string; title: string; pinned: boolean; status: string; lastMessageAt: string; createdAt: string }
interface ConversationMessage { id: string; role: 'USER' | 'ASSISTANT'; status: string; contentMarkdown: string; citations: Citation[]; cacheHit: boolean; durationMs?: number; answerMode?: 'AI' | 'MODEL_QUOTA' | 'EXTRACTIVE' | 'NO_KNOWLEDGE'; modelProvider?: string; modelName?: string; errorMessage?: string; createdAt: string; finishedAt?: string }
interface ConversationDetail { id: string; title: string; createdAt: string; updatedAt: string; messages: ConversationMessage[] }
interface SubmitResult { conversationId: string; assistantMessageId: string }
interface ModelStatus { available: boolean; enabled: boolean; provider: string; modelName: string; reason?: string }

const starters = ['当前最重要的知识结论是什么？', '总结知识库中的关键流程', '最近有哪些知识变化？']

/** 持久化问 Wiki 会话；提交后由服务端后台回答，离开页面不会中断。 */
export default function AskPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const initialQuestion = searchParams.get('q') ?? ''
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [creatingNew, setCreatingNew] = useState(false)
  const [question, setQuestion] = useState('')
  const [useModel, setUseModel] = useState(false)
  const [previewPageId, setPreviewPageId] = useState<string | null>(null)
  const [conversationMenu, setConversationMenu] = useState<{ id: string; x: number; y: number } | null>(null)
  const [renameId, setRenameId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const modelStatus = useQuery({ queryKey: ['query-model-status'], queryFn: () => apiRequest<ModelStatus>('/api/query/model-status') })
  const [following, setFollowing] = useState(true)
  const submittedInitial = useRef(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const conversations = useQuery({
    queryKey: ['query-conversations'],
    queryFn: () => apiRequest<ConversationSummary[]>('/api/query/conversations'),
    refetchInterval: 5000,
  })
  const detail = useQuery({
    queryKey: ['query-conversation', selectedId],
    queryFn: () => apiRequest<ConversationDetail>(`/api/query/conversations/${selectedId}`),
    enabled: Boolean(selectedId),
    refetchInterval: (query) => query.state.data?.messages.some((message) => ['PENDING', 'RUNNING'].includes(message.status)) ? 1500 : false,
  })
  const preview = useQuery({
    queryKey: ['ask-preview-page', previewPageId],
    queryFn: () => apiRequest<PageDetail>(`/api/pages/${previewPageId}`),
    enabled: Boolean(previewPageId),
  })
  const submit = useMutation({
    mutationFn: ({ value, conversationId }: { value: string; conversationId: string | null }) =>
      apiRequest<SubmitResult>(conversationId ? `/api/query/conversations/${conversationId}/messages` : '/api/query/conversations', {
        method: 'POST',
        body: JSON.stringify({ question: value, useModel }),
      }),
    onSuccess: async (result) => {
      setSelectedId(result.conversationId)
      setCreatingNew(false)
      setQuestion('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['query-conversations'] }),
        queryClient.invalidateQueries({ queryKey: ['query-conversation', result.conversationId] }),
      ])
    },
  })
  const updateConversation = useMutation({
    mutationFn: ({ id, title, pinned }: { id: string; title?: string; pinned?: boolean }) => apiRequest<ConversationSummary>(`/api/query/conversations/${id}`, { method: 'PATCH', body: JSON.stringify({ title, pinned }) }),
    onSuccess: async () => { setConversationMenu(null); setRenameId(null); await queryClient.invalidateQueries({ queryKey: ['query-conversations'] }) },
  })
  const deleteConversation = useMutation({
    mutationFn: (id: string) => apiRequest<void>(`/api/query/conversations/${id}`, { method: 'DELETE' }),
    onSuccess: async (_, id) => { setConversationMenu(null); if (selectedId === id) { setSelectedId(null); setCreatingNew(true) }; await queryClient.invalidateQueries({ queryKey: ['query-conversations'] }) },
  })

  /** 首次进入时默认恢复最近会话。 */
  useEffect(() => {
    if (!selectedId && !creatingNew && !initialQuestion && conversations.data?.length) {
      setSelectedId(conversations.data[0].id)
    }
  }, [conversations.data, creatingNew, initialQuestion, selectedId])

  /** 全局搜索携带的问题只创建一次新会话。 */
  useEffect(() => {
    if (initialQuestion && !submittedInitial.current) {
      submittedInitial.current = true
      submit.mutate({ value: initialQuestion, conversationId: null })
      navigate('/ask', { replace: true })
    }
  }, [initialQuestion, navigate, submit])

  const active = detail.data?.messages.some((message) => ['PENDING', 'RUNNING'].includes(message.status)) ?? false
  const messageFingerprint = detail.data?.messages.map((message) => `${message.id}:${message.status}:${message.contentMarkdown.length}`).join('|') ?? ''
  const quotaBlocked = detail.data?.messages.some((message) => message.answerMode === 'MODEL_QUOTA') ?? false

  /** 模型配置不可用时强制关闭本地开关，避免界面显示“已启用”但实际只返回摘录。 */
  useEffect(() => {
    if (modelStatus.data) setUseModel(modelStatus.data.available && !quotaBlocked)
  }, [modelStatus.data?.available, quotaBlocked])

  /** 新消息到达时仅在用户仍位于底部附近时自动跟随，阅读历史内容不会被强制拉走。 */
  useEffect(() => {
    if (!following) return
    const frame = requestAnimationFrame(() => {
      const viewport = scrollRef.current
      if (viewport) viewport.scrollTo({ top: viewport.scrollHeight, behavior: 'smooth' })
    })
    return () => cancelAnimationFrame(frame)
  }, [following, messageFingerprint])

  /** 切换会话默认定位到最新一轮。 */
  useEffect(() => {
    setFollowing(true)
    const frame = requestAnimationFrame(() => {
      const viewport = scrollRef.current
      if (viewport) viewport.scrollTop = viewport.scrollHeight
    })
    return () => cancelAnimationFrame(frame)
  }, [selectedId])

  /** 跳转到当前会话最新消息。 */
  function scrollToLatest() {
    setFollowing(true)
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }

  /** 提交问题；没有选中会话时自动创建新会话。 */
  function ask(value = question) {
    const normalized = value.trim()
    if (!normalized || submit.isPending || active) return
    setFollowing(true)
    submit.mutate({ value: normalized, conversationId: selectedId })
  }

  return <div className="ask-page">
    <PageHeader title="问 Wiki" actions={<button className="button secondary" onClick={() => { setCreatingNew(true); setSelectedId(null); setQuestion('') }}><Plus size={15} />新会话</button>} />
    <div className="ask-layout conversation-layout">
      <aside className="conversation-history">
        <div className="history-title"><strong>历史会话</strong><span>{conversations.data?.length ?? 0}</span></div>
        {updateConversation.error ? <Notice kind="error">{updateConversation.error instanceof ApiError ? updateConversation.error.message : '会话更新失败'}</Notice> : null}{deleteConversation.error ? <Notice kind="error">{deleteConversation.error instanceof ApiError ? deleteConversation.error.message : '会话删除失败'}</Notice> : null}<div className="history-list">
          {conversations.data?.map((conversation) => <button key={conversation.id} className={selectedId === conversation.id ? 'selected' : ''} onContextMenu={(event) => { event.preventDefault(); setConversationMenu({ id: conversation.id, x: event.clientX, y: event.clientY }) }} onClick={() => { setFollowing(true); setCreatingNew(false); setSelectedId(conversation.id) }}><MessageSquarePlus size={15} /><span><strong>{conversation.title}</strong><small>{timeAgo(conversation.lastMessageAt)}</small></span>{conversation.pinned ? <Pin size={12} className="conversation-pin" /> : null}{['PENDING', 'RUNNING'].includes(conversation.status) ? <i className="status-dot" /> : null}</button>)}
          {!conversations.isLoading && !conversations.data?.length ? <p>暂无会话</p> : null}
        </div>
      </aside>

      <section className="conversation-panel">
        {!selectedId && !submit.isPending ? <EmptyState icon={<span className="ask-orb"><Sparkles size={25} /></span>} title="开始新会话" action={<div className="starter-grid">{starters.map((starter) => <button key={starter} onClick={() => ask(starter)}>{starter}<ArrowUp size={14} /></button>)}</div>} /> : null}
        <div className="conversation-scroll" ref={scrollRef} onScroll={(event) => { const viewport = event.currentTarget; setFollowing(viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 90) }}>
          {detail.data?.messages.map((message) => message.role === 'USER'
            ? <article className="query-turn user-turn" key={message.id}><div className="user-question"><span>你</span><p>{message.contentMarkdown}</p></div></article>
            : <article className="query-turn assistant-turn" key={message.id}>
              {message.status === 'FAILED' ? <div className="answer-error">{message.errorMessage || '回答失败'}</div>
                : ['PENDING', 'RUNNING'].includes(message.status) ? <div className="thinking"><span className="ai-mark"><Sparkles size={15} /></span><span><i /><i /><i /></span>{message.status === 'PENDING' ? '等待处理…' : '正在查找答案…'}</div>
                  : <div className={`wiki-answer ${message.answerMode === 'MODEL_QUOTA' ? 'quota-answer' : ''}`}><header><span className="ai-mark"><Sparkles size={15} /></span><div><strong>Wiki 助手</strong><small>{message.answerMode === 'AI' ? `大模型生成 · ${message.modelName ?? message.modelProvider ?? '已配置模型'}` : message.answerMode === 'MODEL_QUOTA' ? '大模型额度已达到上限 · 当前为 Wiki 摘录' : message.answerMode === 'NO_KNOWLEDGE' ? 'Wiki 信息不足' : 'Wiki 知识摘录'} · {message.durationMs ?? 0} 毫秒 · {message.cacheHit ? '缓存' : '实时'}</small></div><button className="icon-button" onClick={() => navigator.clipboard.writeText(message.contentMarkdown)} aria-label="复制回答"><Copy size={15} /></button></header><div className="markdown-body compact"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.contentMarkdown}</ReactMarkdown></div><div className="inline-citations">{message.citations.map((citation) => <button key={citation.number} onClick={() => setPreviewPageId(citation.pageId)}><span>{citation.number}</span>{citation.title}</button>)}</div></div>}
            </article>)}
          {submit.isPending ? <div className="thinking"><span className="ai-mark"><Sparkles size={15} /></span>正在提交…</div> : null}
        </div>
        {!following ? <button className="jump-latest" onClick={scrollToLatest}><ArrowDown size={14} />最新消息</button> : null}
        {submit.error ? <div className="answer-error">{submit.error instanceof ApiError ? submit.error.message : '提交失败'}</div> : null}
      <form className="ask-composer" onSubmit={(event) => { event.preventDefault(); ask() }}><textarea value={question} onChange={(event) => setQuestion(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); ask() } }} placeholder="输入问题…" /><footer><label className={`model-switch ${!modelStatus.data?.available ? 'disabled' : ''}`} data-tooltip={!modelStatus.data?.available ? (modelStatus.data?.reason ?? '模型当前不可用') : undefined}><input type="checkbox" checked={useModel && Boolean(modelStatus.data?.available)} disabled={!modelStatus.data?.available} onChange={(event) => setUseModel(event.target.checked)} /><span>启用大模型对话</span></label><span>{active ? '当前回答完成后可继续提问' : useModel ? `将使用 ${modelStatus.data?.modelName ?? '已配置模型'} 生成回答` : ''}</span><button className="send-button" disabled={!question.trim() || submit.isPending || active} aria-label="发送"><ArrowUp size={18} /></button></footer></form>
      </section>
      {conversationMenu ? <div className="conversation-context-menu" style={{ left: conversationMenu.x, top: conversationMenu.y }} onMouseLeave={() => setConversationMenu(null)}><button onClick={() => { updateConversation.mutate({ id: conversationMenu.id, pinned: !(conversations.data?.find((item) => item.id === conversationMenu.id)?.pinned ?? false) }) }}><Pin size={14} />{conversations.data?.find((item) => item.id === conversationMenu.id)?.pinned ? '取消置顶' : '置顶'}</button><button onClick={() => { const item = conversations.data?.find((entry) => entry.id === conversationMenu.id); setRenameId(conversationMenu.id); setRenameValue(item?.title ?? ''); setConversationMenu(null) }}><Pencil size={14} />重命名</button><button className="danger-text" onClick={() => { if (window.confirm('确定删除这个会话吗？删除后无法恢复。')) deleteConversation.mutate(conversationMenu.id) }}><Trash2 size={14} />删除</button></div> : null}
      {renameId ? <Modal title="重命名会话" onClose={() => setRenameId(null)}><form className="rename-form" onSubmit={(event) => { event.preventDefault(); updateConversation.mutate({ id: renameId, title: renameValue }) }}><input value={renameValue} onChange={(event) => setRenameValue(event.target.value)} autoFocus maxLength={80} /><footer><button type="button" className="button ghost" onClick={() => setRenameId(null)}>取消</button><button className="button primary" disabled={!renameValue.trim() || updateConversation.isPending}>保存</button></footer></form></Modal> : null}
      {previewPageId ? <Modal title={preview.data?.title ?? '文档预览'} onClose={() => setPreviewPageId(null)} wide>{preview.isLoading ? <LoadingBlock rows={8} /> : preview.data ? <div className="ask-document-preview"><div className="reader-meta"><Badge value={preview.data.pageType} /><span>{timeAgo(preview.data.updatedAt)} 更新</span></div><div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{preview.data.contentMarkdown}</ReactMarkdown></div><footer><button className="button secondary" onClick={() => setPreviewPageId(null)}>关闭</button><button className="button primary" onClick={() => { setPreviewPageId(null); navigate(`/knowledge/${preview.data?.id}`) }}>打开知识库</button></footer></div> : <EmptyState title="文档不存在" />}</Modal> : null}
    </div>
  </div>
}
