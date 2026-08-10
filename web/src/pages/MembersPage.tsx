import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, Plus, ShieldCheck, UserPlus, Users } from 'lucide-react'
import { useState } from 'react'
import { Badge, EmptyState, Modal, Notice, PageHeader, timeAgo } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'

interface Member { id: string; email: string; displayName: string; status: string; roles: string[]; joinedAt: string }
interface Role { id: string; code: string; name: string; description: string; systemRole: boolean; permissions: string[] }

/** 成员与 RBAC 管理页，角色变更由后端保证至少保留一名管理员。 */
export default function MembersPage() {
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<Member | null>(null)
  const members = useQuery({ queryKey: ['members'], queryFn: () => apiRequest<Member[]>('/api/admin/members') })
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => apiRequest<Role[]>('/api/admin/roles') })

  return <div className="standard-page members-page"><PageHeader title="成员与角色" actions={<button className="button primary" onClick={() => setAdding(true)}><UserPlus size={16} />添加成员</button>} />
    <div className="member-metrics"><div><Users /><span><strong>{members.data?.length ?? 0}</strong><small>空间成员</small></span></div><div><ShieldCheck /><span><strong>{roles.data?.length ?? 0}</strong><small>可用角色</small></span></div><div><KeyRound /><span><strong>{roles.data?.reduce((total, role) => total + role.permissions.length, 0) ?? 0}</strong><small>权限映射</small></span></div></div>
    <section className="panel"><div className="panel-header"><div><h2>当前成员</h2></div></div><div className="member-table"><div className="member-row header"><span>成员</span><span>状态</span><span>角色</span><span>加入时间</span><span /></div>{members.data?.map((member) => <div className="member-row" key={member.id}><span className="member-identity"><i>{member.displayName.slice(0, 1)}</i><span><strong>{member.displayName}</strong><small>{member.email}</small></span></span><span><Badge value={member.status} /></span><span className="role-chips">{member.roles.map((role) => <em key={role}>{roleLabel(role)}</em>)}</span><span>{timeAgo(member.joinedAt)}</span><span><button className="button tiny" onClick={() => setEditing(member)}>编辑角色</button></span></div>)}{!members.isLoading && !members.data?.length ? <EmptyState icon={<Users size={24} />} title="没有成员" description="添加第一位贡献者。" /> : null}</div></section>
    <section className="role-grid">{roles.data?.map((role) => <article key={role.id}><header><span className="role-symbol"><ShieldCheck size={18} /></span><div><h3>{role.name}</h3><small>{roleLabel(role.code)}</small></div>{role.systemRole ? <Badge value="SYSTEM" /> : <Badge value="CUSTOM" />}</header><p>{role.description}</p><footer><strong>{role.permissions.length}</strong> 项权限<span>{role.permissions.slice(0, 3).map(permissionLabel).join(' · ')}</span></footer></article>)}</section>
    {adding ? <AddMemberModal roles={roles.data ?? []} onClose={() => setAdding(false)} onSaved={() => { setAdding(false); queryClient.invalidateQueries({ queryKey: ['members'] }) }} /> : null}
    {editing ? <EditRolesModal member={editing} roles={roles.data ?? []} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); queryClient.invalidateQueries({ queryKey: ['members'] }) }} /> : null}
  </div>
}

/** 添加成员表单。 */
function AddMemberModal({ roles, onClose, onSaved }: { roles: Role[]; onClose: () => void; onSaved: () => void }) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [roleCodes, setRoleCodes] = useState<string[]>(['CONTRIBUTOR'])
  const mutation = useMutation({ mutationFn: () => apiRequest<Member>('/api/admin/members', { method: 'POST', body: JSON.stringify({ email, displayName, temporaryPassword, roleCodes }) }), onSuccess: onSaved })
  return <Modal title="添加空间成员" onClose={onClose}><form className="stack-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><label>企业邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label><label>姓名<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} required /></label><label>新用户临时密码<input type="password" minLength={12} value={temporaryPassword} onChange={(event) => setTemporaryPassword(event.target.value)} placeholder="已有账号可留空" /></label><RoleSelector roles={roles} selected={roleCodes} onChange={setRoleCodes} /><Notice>贡献者新增页面需要审核；编辑者可直接新增，但修改已有页面仍需审核。</Notice>{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '添加失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={mutation.isPending}>添加成员</button></footer></form></Modal>
}

/** 编辑成员角色表单。 */
function EditRolesModal({ member, roles, onClose, onSaved }: { member: Member; roles: Role[]; onClose: () => void; onSaved: () => void }) {
  const [selected, setSelected] = useState(member.roles)
  const mutation = useMutation({ mutationFn: () => apiRequest<Member>(`/api/admin/members/${member.id}/roles`, { method: 'PUT', body: JSON.stringify({ roleCodes: selected }) }), onSuccess: onSaved })
  return <Modal title={`编辑 ${member.displayName} 的角色`} onClose={onClose}><form className="stack-form" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}><RoleSelector roles={roles} selected={selected} onChange={setSelected} /><Notice kind="warning">不能移除最后一位组织管理员。</Notice>{mutation.error ? <div className="form-error">{mutation.error instanceof ApiError ? mutation.error.message : '更新失败'}</div> : null}<footer className="form-actions"><button type="button" className="button ghost" onClick={onClose}>取消</button><button className="button primary" disabled={!selected.length || mutation.isPending}>保存角色</button></footer></form></Modal>
}

/** 角色多选器。 */
function RoleSelector({ roles, selected, onChange }: { roles: Role[]; selected: string[]; onChange: (roles: string[]) => void }) {
  return <fieldset className="role-selector"><legend>分配角色</legend>{roles.map((role) => <label key={role.id}><input type="checkbox" checked={selected.includes(role.code)} onChange={(event) => onChange(event.target.checked ? [...selected, role.code] : selected.filter((code) => code !== role.code))} /><span><strong>{role.name}</strong><small>{role.description}</small></span></label>)}</fieldset>
}

/** 将系统角色代码转换为中文。 */
function roleLabel(role: string) {
  return ({ ORG_ADMIN: '组织管理员', WORKSPACE_ADMIN: '空间管理员', EDITOR: '编辑者', CONTRIBUTOR: '贡献者', REVIEWER: '审核者', VIEWER: '访客' } as Record<string, string>)[role] ?? role
}

/** 将常用权限代码转换为简短中文。 */
function permissionLabel(permission: string) {
  return ({ PAGE_READ: '查看页面', PAGE_CREATE: '新建页面', SOURCE_CREATE: '添加来源', REVIEW_READ: '查看审核', REVIEW_DECIDE: '处理审核', MEMBER_MANAGE: '管理成员', MODEL_MANAGE: '管理模型', AUTOMATION_MANAGE: '管理自动化', QUERY_EXECUTE: '问 Wiki' } as Record<string, string>)[permission] ?? permission
}
