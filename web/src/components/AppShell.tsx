import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bot,
  BookOpen,
  Boxes,
  ChevronDown,
  CircleHelp,
  Database,
  FileCheck2,
  History,
  Clock3,
  LayoutDashboard,
  LogOut,
  Network,
  Plus,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  Users,
} from 'lucide-react'
import { useState, type ComponentType } from 'react'
import { useMemo } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { apiRequest } from '../lib/api'

interface WorkspaceOption {
  organizationId: string
  organizationName: string
  workspaceId: string
  workspaceName: string
  description: string
}

interface NavigationItem {
  to: string
  label: string
  icon: ComponentType<{ size?: number; strokeWidth?: number }>
  permission?: string
}

interface GlobalSearchPage { id: string; title: string; pageType: string }
interface GlobalSearchSource { id: string; title: string; sourceType: string }

const navigation: NavigationItem[] = [
  { to: '/dashboard', label: '工作台', icon: LayoutDashboard, permission: 'PAGE_READ' },
  { to: '/knowledge', label: '知识库', icon: BookOpen, permission: 'PAGE_READ' },
  { to: '/ask', label: '问 Wiki', icon: Sparkles, permission: 'QUERY_EXECUTE' },
  { to: '/sources', label: '资料来源', icon: Database, permission: 'SOURCE_READ' },
  { to: '/review', label: '审核中心', icon: FileCheck2, permission: 'REVIEW_READ' },
  { to: '/evolution', label: '持续优化记录', icon: History, permission: 'AUTOMATION_READ' },
  { to: '/ai-tasks', label: 'AI 定时任务', icon: Clock3, permission: 'AUTOMATION_READ' },
  { to: '/automation', label: '模型与 MCP', icon: Bot, permission: 'AUTOMATION_READ' },
  { to: '/graph', label: '知识图谱', icon: Network, permission: 'PAGE_READ' },
  { to: '/members', label: '成员与角色', icon: Users, permission: 'MEMBER_MANAGE' },
  { to: '/settings', label: '空间设置', icon: Settings },
]

/** 企业应用壳：提供租户切换、权限感知导航、全局查询入口和用户会话操作。 */
export function AppShell() {
  const { user, logout, switchWorkspace, hasPermission } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [workspaceOpen, setWorkspaceOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const [search, setSearch] = useState('')
  const pagesSearch = useQuery({ queryKey: ['global-search-pages'], queryFn: () => apiRequest<GlobalSearchPage[]>('/api/pages?limit=100'), enabled: hasPermission('PAGE_READ') })
  const sourcesSearch = useQuery({ queryKey: ['global-search-sources'], queryFn: () => apiRequest<GlobalSearchSource[]>('/api/sources?limit=100'), enabled: hasPermission('SOURCE_READ') })
  const workspaces = useQuery({
    queryKey: ['workspaces'],
    queryFn: () => apiRequest<WorkspaceOption[]>('/api/auth/workspaces'),
  })

  /** 清空租户相关缓存后切换空间。 */
  async function chooseWorkspace(workspaceId: string) {
    if (workspaceId === user?.workspaceId) return setWorkspaceOpen(false)
    await switchWorkspace(workspaceId)
    queryClient.clear()
    setWorkspaceOpen(false)
    navigate('/dashboard')
  }

  const searchResults = useMemo(() => {
    const value = search.trim().toLowerCase()
    if (value.length < 2) return []
    return [
      ...(pagesSearch.data ?? []).filter((item) => item.title.toLowerCase().includes(value)).slice(0, 6).map((item) => ({ kind: 'page' as const, id: item.id, title: item.title, label: item.pageType === 'ENTITY' ? '实体' : '页面' })),
      ...(sourcesSearch.data ?? []).filter((item) => item.title.toLowerCase().includes(value)).slice(0, 4).map((item) => ({ kind: 'source' as const, id: item.id, title: item.title, label: '来源' })),
    ].slice(0, 8)
  }, [pagesSearch.data, search, sourcesSearch.data])

  /** 将全局搜索定位到页面或来源，不把搜索词当作问 Wiki 问题。 */
  function submitSearch(event: React.FormEvent) {
    event.preventDefault()
    const first = searchResults[0]
    if (!first) return
    navigate(first.kind === 'page' ? `/knowledge/${first.id}` : `/sources?search=${encodeURIComponent(first.title)}`)
    setSearch('')
  }

  /** 打开全局搜索结果对应的页面或来源列表。 */
  function chooseSearchResult(result: (typeof searchResults)[number]) {
    navigate(result.kind === 'page' ? `/knowledge/${result.id}` : `/sources?search=${encodeURIComponent(result.title)}`)
    setSearch('')
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark"><Boxes size={19} /></span><span>LLM Wiki</span></div>
        <div className="workspace-switcher">
          <button className="workspace-button" onClick={() => setWorkspaceOpen((open) => !open)} aria-expanded={workspaceOpen}>
            <span className="workspace-avatar">{user?.organizationName.slice(0, 1)}</span>
            <span><strong>{user?.organizationName}</strong><small>{user?.workspaceName}</small></span>
            <ChevronDown size={16} />
          </button>
          {workspaceOpen ? (
            <div className="popover workspace-menu">
              <div className="popover-label">可访问空间</div>
              {workspaces.data?.map((workspace) => (
                <button key={workspace.workspaceId} onClick={() => chooseWorkspace(workspace.workspaceId)}>
                  <span><strong>{workspace.workspaceName}</strong><small>{workspace.organizationName}</small></span>
                  {workspace.workspaceId === user?.workspaceId ? <ShieldCheck size={16} className="success" /> : null}
                </button>
              ))}
              {hasPermission('MEMBER_MANAGE') ? <button onClick={() => navigate('/settings')}><Plus size={15} />创建新空间</button> : null}
            </div>
          ) : null}
        </div>
        <nav className="main-nav" aria-label="主导航">
          {navigation.filter((item) => !item.permission || hasPermission(item.permission)).map((item) => {
            const Icon = item.icon
            return (
              <NavLink key={item.to} to={item.to} className={({ isActive }) => isActive ? 'active' : ''}>
                <Icon size={18} strokeWidth={1.8} /><span>{item.label}</span>
              </NavLink>
            )
          })}
        </nav>
        <div className="sidebar-footer">
          <div className="plan-card"><ShieldCheck size={18} /><span><strong>企业工作空间</strong><small>RLS 租户隔离已启用</small></span></div>
          <div className="build-status"><span className="status-dot success-dot" />AI 编译服务运行中</div>
        </div>
      </aside>

      <div className="app-main">
        <header className="topbar">
          <form className="global-search-wrap" onSubmit={submitSearch}>
            <div className="global-search"><Search size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索页面、来源或实体…" />
            <kbd>⌘ K</kbd>
            </div>
            {search.length >= 2 ? <div className="global-search-results">{searchResults.map((result) => <button type="button" key={`${result.kind}-${result.id}`} onClick={() => chooseSearchResult(result)}><span>{result.title}</span><small>{result.label}</small></button>)}{!searchResults.length ? <p>没有找到页面、来源或实体</p> : null}</div> : null}
          </form>
          <div className="topbar-actions">
            <button className="icon-button" aria-label="帮助"><CircleHelp size={18} /></button>
            <div className="profile-wrap">
              <button className="profile-button" onClick={() => setProfileOpen((open) => !open)}>
                <span className="user-avatar">{user?.displayName.slice(0, 1)}</span>
                <span>{user?.displayName}</span><ChevronDown size={15} />
              </button>
              {profileOpen ? (
                <div className="popover profile-menu">
                  <div className="profile-summary"><strong>{user?.displayName}</strong><small>{user?.email}</small></div>
                  <button onClick={() => navigate('/settings')}><Settings size={15} />空间设置</button>
                  <button onClick={() => logout()}><LogOut size={15} />退出登录</button>
                </div>
              ) : null}
            </div>
          </div>
        </header>
        <main className={`content-area route-${location.pathname.split('/')[1] || 'dashboard'}`}>
          <Outlet />
        </main>
      </div>
    </div>
  )
}
