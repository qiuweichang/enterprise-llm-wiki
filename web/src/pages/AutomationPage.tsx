import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Copy, FileKey2, KeyRound, Network, Plus, RefreshCw, SlidersHorizontal, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { Badge, EmptyState, Modal, Notice, PageHeader, timeAgo } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'
import { SemanticPanel } from '../components/SemanticPanel'

interface ApiKeyView { id: string; name: string; tokenPrefix: string; expiresAt?: string; lastUsedAt?: string; revokedAt?: string; createdAt: string }
interface CreatedApiKey { id: string; name: string; token: string; expiresAt?: string }
interface ModelSettings { enabled: boolean; provider: string; baseUrl: string; modelName: string; apiKeyConfigured: boolean; inheritsEnvironmentKey: boolean; apiKeyHint?: string; temperature: number; maxTokens: number; source: string; updatedAt?: string; credentialStatus: string; credentialMessage?: string }
interface ModelTestResult { success: boolean; latencyMs: number; responsePreview: string }

/** 仅管理空间级大模型与 MCP 接入配置，持续优化的控制和记录由独立页面承载。 */
export default function AutomationPage() {
  const queryClient = useQueryClient()
  const { hasPermission } = useAuth()
  const [createOpen, setCreateOpen] = useState(false)
  const [created, setCreated] = useState<CreatedApiKey | null>(null)
  const canManageModel = hasPermission('MODEL_MANAGE')
  const canUseMcp = hasPermission('MCP_USE')
  const keys = useQuery({ queryKey: ['api-keys'], queryFn: () => apiRequest<ApiKeyView[]>('/api/auth/api-keys'), enabled: canUseMcp })
  const model = useQuery({ queryKey: ['model-settings'], queryFn: () => apiRequest<ModelSettings>('/api/automation/model'), enabled: canManageModel })
  const revoke = useMutation({ mutationFn: (id: string) => apiRequest<void>(`/api/auth/api-keys/${id}/revoke`, { method: 'POST' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['api-keys'] }) })

  return <div className="standard-page automation-page"><PageHeader title="模型与 MCP" actions={canUseMcp ? <button className="button primary" onClick={() => setCreateOpen(true)}><Plus size={16} />创建 MCP 密钥</button> : null} />
    {canManageModel && model.data ? <ModelConfiguration key={`${model.data.source}-${model.data.updatedAt ?? ''}`} model={model.data} onSaved={(value) => queryClient.setQueryData(['model-settings'], value)} /> : null}

    {canManageModel ? <SemanticPanel /> : null}
    {canUseMcp ? <div className="automation-grid">
      <section className="panel"><div className="panel-header"><div><h2><Network size={18} />MCP 接入</h2><p>协议版本 2025-03-26</p></div><span className="live-status"><i />可用</span></div><Notice kind="success"><CheckCircle2 size={16} />MCP 工具继承组织、空间和权限；更新已有页面仍会进入审核。</Notice><div className="code-card"><header><span>客户端配置</span><button onClick={() => navigator.clipboard.writeText(mcpConfig())}><Copy size={14} />复制</button></header><pre>{mcpConfig()}</pre></div><div className="tool-list"><span>llm_wiki_query</span><span>llm_wiki_list_pages</span><span>llm_wiki_propose_page</span><span>llm_wiki_add_text_source</span><span>llm_wiki_review_change</span></div></section>
      <section className="panel"><div className="panel-header"><div><h2><KeyRound size={18} />接口密钥</h2><p>仅在创建时显示一次</p></div><button className="icon-button" aria-label="刷新 MCP 密钥" onClick={() => keys.refetch()}><RefreshCw size={15} /></button></div><div className="key-list">{keys.data?.map((key) => <article key={key.id} className={key.revokedAt ? 'revoked' : ''}><span className="key-icon"><FileKey2 size={17} /></span><div><strong>{key.name}</strong><small>{key.tokenPrefix}•••• · 创建于 {timeAgo(key.createdAt)}</small><em>{key.revokedAt ? '已撤销' : key.lastUsedAt ? `最近使用 ${timeAgo(key.lastUsedAt)}` : '尚未使用'}</em></div>{!key.revokedAt ? <button className="icon-button danger" onClick={() => revoke.mutate(key.id)} aria-label="撤销"><Trash2 size={15} /></button> : null}</article>)}{!keys.isLoading && !keys.data?.length ? <EmptyState icon={<KeyRound size={24} />} title="还没有接口密钥" description="为 MCP 客户端创建当前空间的密钥。" /> : null}</div></section>
    </div> : null}
    {createOpen ? <CreateKeyModal onClose={() => setCreateOpen(false)} onCreated={(value) => { setCreateOpen(false); setCreated(value); queryClient.invalidateQueries({ queryKey: ['api-keys'] }) }} /> : null}
    {created ? <Modal title="请立即保存接口密钥" onClose={() => setCreated(null)}><div className="secret-result"><Notice kind="warning">关闭后无法再次查看；如丢失，请撤销并重新创建。</Notice><code>{created.token}</code><button className="button primary" onClick={() => navigator.clipboard.writeText(created.token)}><Copy size={15} />复制密钥</button></div></Modal> : null}
  </div>
}

/** 空间级模型编辑器；连接参数和密钥必须通过真实调用测试后才能保存。 */
function ModelConfiguration({ model, onSaved }: { model: ModelSettings; onSaved: (value: ModelSettings) => void }) {
  const [form, setForm] = useState({ ...model, apiKey: '', clearApiKey: false })
  const [testedSignature, setTestedSignature] = useState('')
  const payload = { enabled: form.enabled, provider: form.provider, baseUrl: form.baseUrl, modelName: form.modelName, apiKey: form.apiKey, clearApiKey: form.clearApiKey, temperature: form.temperature, maxTokens: form.maxTokens }
  const signature = JSON.stringify(payload)
  const test = useMutation({ mutationFn: () => apiRequest<ModelTestResult>('/api/automation/model/test', { method: 'POST', body: JSON.stringify(payload) }), onSuccess: () => setTestedSignature(signature) })
  /** 参数变化后立即废弃旧测试结果，保证被保存的正是刚才验证成功的配置。 */
  useEffect(() => {
    setTestedSignature('')
    test.reset()
    // mutation 对象随渲染更新；这里只依赖稳定的配置签名。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature])
  const save = useMutation({ mutationFn: () => apiRequest<ModelSettings>('/api/automation/model', { method: 'PUT', body: JSON.stringify(payload) }), onSuccess: onSaved })
  const tested = testedSignature === signature
  const connectionChanged = form.provider !== model.provider || form.baseUrl !== model.baseUrl || form.modelName !== model.modelName || form.temperature !== model.temperature || form.maxTokens !== model.maxTokens || Boolean(form.apiKey.trim()) || form.clearApiKey
  const requiresTest = form.enabled || (connectionChanged && !(!form.enabled && form.clearApiKey))
  const credentialInvalid = model.credentialStatus === 'INVALID'
  const keyPlaceholder = credentialInvalid ? '原密钥已失效，请重新输入' : model.inheritsEnvironmentKey ? '当前来自环境变量；留空将继续安全继承' : model.apiKeyConfigured ? `已保存：${model.apiKeyHint ?? '加密密钥'}` : '本地模型可留空'
  return <section className={`panel model-configuration ${credentialInvalid ? 'model-credential-invalid' : ''}`}><div className="panel-header"><h2><SlidersHorizontal size={18} />大模型配置</h2><Badge value={credentialInvalid ? 'INVALID' : form.enabled ? 'ENABLED' : 'DISABLED'} /></div>{credentialInvalid ? <Notice kind="error">{model.credentialMessage ?? '接口密钥已失效，请重新输入密钥并测试保存。持续优化已暂停。'}</Notice> : null}<div className="model-config-grid"><label>接口类型<select value={form.provider} onChange={(event) => setForm((value) => ({ ...value, provider: event.target.value }))}><option value="OPENAI_COMPATIBLE">OpenAI 兼容接口</option><option value="OPENAI">OpenAI</option><option value="OLLAMA">Ollama</option></select></label><label>模型名称<input value={form.modelName} onChange={(event) => setForm((value) => ({ ...value, modelName: event.target.value }))} placeholder="例如：qwen3" /></label><label className="wide">服务地址<input value={form.baseUrl} onChange={(event) => setForm((value) => ({ ...value, baseUrl: event.target.value }))} placeholder="例如：https://api.openai.com/v1" /></label><label>接口密钥<input type="password" value={form.apiKey} onChange={(event) => setForm((value) => ({ ...value, apiKey: event.target.value, clearApiKey: false }))} placeholder={keyPlaceholder} /></label><label>温度<input type="number" min="0" max="2" step="0.1" value={form.temperature} onChange={(event) => setForm((value) => ({ ...value, temperature: Number(event.target.value) }))} /></label><label>最大输出<select value={form.maxTokens} onChange={(event) => setForm((value) => ({ ...value, maxTokens: Number(event.target.value) }))}><option value={2048}>2048</option><option value={4096}>4096</option><option value={8192}>8192</option><option value={16384}>16384</option></select></label></div><div className="model-config-actions"><label className="inline-check"><input type="checkbox" checked={form.enabled} onChange={(event) => setForm((value) => ({ ...value, enabled: event.target.checked }))} />启用</label>{model.apiKeyConfigured ? <label className="inline-check danger-text"><input type="checkbox" checked={form.clearApiKey} onChange={(event) => setForm((value) => ({ ...value, clearApiKey: event.target.checked, apiKey: '' }))} />{model.inheritsEnvironmentKey ? '停止继承环境密钥' : '清除密钥'}</label> : null}<span className="model-source">来源：{model.source === 'WORKSPACE' ? '当前空间' : '环境变量'}</span><button className="button secondary" disabled={test.isPending} onClick={() => test.mutate()}>{test.isPending ? '测试中…' : '测试连接'}</button><button className="button primary" disabled={save.isPending || (requiresTest && !tested)} onClick={() => save.mutate()}>{save.isPending ? '保存中…' : '保存'}</button></div>{test.data && tested ? <Notice kind="success">连接成功，耗时 {test.data.latencyMs} 毫秒：{test.data.responsePreview}</Notice> : null}{test.error ? <div className="form-error">{test.error instanceof ApiError ? test.error.message : '连接失败'}</div> : null}{save.error ? <div className="form-error">{save.error instanceof ApiError ? save.error.message : '保存失败'}</div> : null}</section>
}

/** 创建绑定当前租户和空间的 MCP 接口密钥。 */
function CreateKeyModal({ onClose, onCreated }: { onClose: () => void; onCreated: (key: CreatedApiKey) => void }) {
  const [name, setName] = useState('我的 MCP 客户端')
  const [expiresAt, setExpiresAt] = useState('')
  const mutation = useMutation({ mutationFn: () => apiRequest<CreatedApiKey>('/api/auth/api-keys', { method: 'POST', body: JSON.stringify({ name, expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null }) }), onSuccess: onCreated })
  return <Modal title="创建 MCP 接口密钥" onClose={onClose}><form className="stack-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><label>密钥名称<input value={name} onChange={(event) => setName(event.target.value)} required /></label><label>过期时间（可选）<input type="datetime-local" value={expiresAt} onChange={(event) => setExpiresAt(event.target.value)} /></label><Notice>密钥绑定当前组织与空间，实际能力随角色权限变化。</Notice>{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '创建失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={mutation.isPending}>创建密钥</button></footer></form></Modal>
}

/** 返回不含真实密钥的 MCP 客户端配置示例。 */
function mcpConfig() {
  return `{
  "mcpServers": {
    "llm-wiki": {
      "type": "http",
      "url": "http://localhost:8123/mcp",
      "headers": { "Authorization": "Bearer lwk_..." }
    }
  }
}`
}
