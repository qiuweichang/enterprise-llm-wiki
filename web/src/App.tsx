import { lazy, Suspense } from 'react'
import { Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { AppShell } from './components/AppShell'

const LoginPage = lazy(() => import('./pages/LoginPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const KnowledgePage = lazy(() => import('./pages/KnowledgePage'))
const AskPage = lazy(() => import('./pages/AskPage'))
const SourcesPage = lazy(() => import('./pages/SourcesPage'))
const ReviewPage = lazy(() => import('./pages/ReviewPage'))
const AutomationPage = lazy(() => import('./pages/AutomationPage'))
const EvolutionPage = lazy(() => import('./pages/EvolutionPage'))
const AiTasksPage = lazy(() => import('./pages/AiTasksPage'))
const GraphPage = lazy(() => import('./pages/GraphPage'))
const MembersPage = lazy(() => import('./pages/MembersPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))

/** 保护需要登录的路由并等待刷新会话完成。 */
function ProtectedRoutes() {
  const { user, ready } = useAuth()
  if (!ready) return <div className="app-loader"><span className="spinner" />正在恢复安全会话…</div>
  if (!user) return <Navigate to="/login" replace />
  return <Outlet />
}

/** 应用路由，重型业务页使用 lazy 分包以缩短首屏登录加载。 */
export function App() {
  return (
    <Suspense fallback={<div className="app-loader"><span className="spinner" />正在加载…</div>}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoutes />}>
          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/knowledge" element={<KnowledgePage />} />
            <Route path="/knowledge/:pageId" element={<KnowledgePage />} />
            <Route path="/ask" element={<AskPage />} />
            <Route path="/sources" element={<SourcesPage />} />
            <Route path="/review" element={<ReviewPage />} />
            <Route path="/review/:changeSetId" element={<ReviewPage />} />
            <Route path="/automation" element={<AutomationPage />} />
            <Route path="/evolution" element={<EvolutionPage />} />
            <Route path="/ai-tasks" element={<AiTasksPage />} />
            <Route path="/graph" element={<GraphPage />} />
            <Route path="/members" element={<MembersPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </Suspense>
  )
}
