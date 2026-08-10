import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, Boxes, CheckCircle2, Download, ExternalLink, Plus, Server, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { Modal, Notice, PageHeader } from '../components/Ui'
import { ApiError, apiRequest, downloadAuthenticated } from '../lib/api'

interface WorkspaceOption { organizationId: string; organizationName: string; workspaceId: string; workspaceName: string; description: string }

/** 空间设置与可移植导出页。 */
export default function SettingsPage() {
  const { user, hasPermission } = useAuth()
  const [creating, setCreating] = useState(false)
  const [exporting, setExporting] = useState(false)
  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: () => apiRequest<WorkspaceOption[]>('/api/auth/workspaces') })

  /** 下载数据库快照生成的 Obsidian Vault。 */
  async function exportWiki() {
    setExporting(true)
    try { await downloadAuthenticated('/api/export/obsidian', 'llm-wiki-obsidian.zip') } finally { setExporting(false) }
  }

  return <div className="standard-page settings-page"><PageHeader title="空间设置" actions={hasPermission('MEMBER_MANAGE') ? <button className="button primary" onClick={() => setCreating(true)}><Plus size={16} />创建空间</button> : undefined} />
    <section className="settings-grid"><article className="panel setting-card"><header><span><Boxes /></span><div><h2>当前工作空间</h2><p>所有 API 请求固定在这个租户上下文。</p></div></header><dl><div><dt>组织</dt><dd>{user?.organizationName}</dd></div><div><dt>空间</dt><dd>{user?.workspaceName}</dd></div><div><dt>空间 ID</dt><dd><code>{user?.workspaceId}</code></dd></div></dl><Notice kind="success"><ShieldCheck size={16} />应用过滤 + PostgreSQL 强制 RLS 双层隔离。</Notice></article>
      <article className="panel setting-card"><header><span><Download /></span><div><h2>通用 Wiki 导出</h2><p>可由 Obsidian 直接打开。</p></div></header><ul className="check-list"><li><CheckCircle2 />YAML 元数据与稳定页面 ID</li><li><CheckCircle2 />Wiki 链接、来源和修订号</li><li><CheckCircle2 />近期审计与 Obsidian 配置</li></ul><button className="button primary full" onClick={exportWiki} disabled={exporting || !hasPermission('EXPORT_WIKI')}><Archive size={16} />{exporting ? '正在生成快照…' : '导出 Obsidian 知识库'}</button></article>
      <article className="panel setting-card"><header><span><Server /></span><div><h2>基础设施</h2></div></header><div className="infra-list"><div><i className="success-dot" /><span><strong>PostgreSQL</strong><small>事实、事务、租户隔离与检索</small></span></div><div><i className="success-dot" /><span><strong>Redis</strong><small>查询缓存与版本失效</small></span></div><div><i className="success-dot" /><span><strong>Python 提取服务</strong><small>网页、OCR 与音视频转写</small></span></div></div></article>
    </section>
    <section className="panel workspace-list-panel"><div className="panel-header"><div><h2>我可访问的空间</h2><p>切换空间会立即使旧访问令牌失效，并重新加载权限。</p></div></div>{workspaces.data?.map((workspace) => <div className="workspace-list-row" key={workspace.workspaceId}><span className="workspace-avatar">{workspace.workspaceName.slice(0, 1)}</span><span><strong>{workspace.workspaceName}</strong><small>{workspace.organizationName} · {workspace.description}</small></span>{workspace.workspaceId === user?.workspaceId ? <span className="current-label">当前</span> : null}</div>)}</section>
    {creating ? <CreateWorkspaceModal onClose={() => setCreating(false)} /> : null}
  </div>
}

/** 创建同组织空间表单。 */
function CreateWorkspaceModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const mutation = useMutation({ mutationFn: () => apiRequest('/api/admin/workspaces', { method: 'POST', body: JSON.stringify({ name, description }) }), onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['workspaces'] }); onClose() } })
  return <Modal title="创建企业知识空间" onClose={onClose}><form className="stack-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><label>空间名称<input value={name} onChange={(event) => setName(event.target.value)} required /></label><label>用途说明<textarea value={description} onChange={(event) => setDescription(event.target.value)} placeholder="例如：研发制度与技术决策" /></label><Notice>你会自动成为新空间管理员；创建后可从左上角切换。</Notice>{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '创建失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={mutation.isPending}>创建空间</button></footer></form></Modal>
}
