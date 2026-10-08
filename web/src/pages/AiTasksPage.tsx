import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BrainCircuit, History, Pause, Play, Plus, RefreshCw, Save, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { Badge, EmptyState, Modal, Notice, PageHeader, timeAgo, timeUntil } from '../components/Ui'
import { ApiError, apiRequest } from '../lib/api'

interface AiTask { id: string; name: string; prompt: string; taskType: 'GENERIC' | 'AI_VENDOR_NEWS'; collectionWindowDays: number; frequency: 'DAILY' | 'WEEKLY' | 'INTERVAL'; runTime: string; dayOfWeek?: number; intervalMinutes?: number; enabled: boolean; nextRunAt: string; lastRunAt?: string; createdAt: string }
interface AiTaskRun { id: string; taskId: string; taskName: string; status: string; triggerType: string; modelProvider?: string; modelName?: string; generatedSourceId?: string; ingestionJobId?: string; collectionDetails?: { fromDate?: string; toDate?: string; captured?: number; failed?: number; failures?: string[]; urls?: string[] }; resultSummary?: string; errorMessage?: string; startedAt?: string; finishedAt?: string; createdAt: string }
interface AiTaskForm { name: string; prompt: string; taskType: 'GENERIC' | 'AI_VENDOR_NEWS'; collectionWindowDays: number; frequency: 'DAILY' | 'WEEKLY' | 'INTERVAL'; runTime: string; dayOfWeek: number; intervalMinutes: number; enabled: boolean }

/** 独立管理 AI 定时任务及其处理记录，持续刷新任务状态但不加载持续优化历史。 */
export default function AiTasksPage() {
  const queryClient = useQueryClient()
  const { hasPermission } = useAuth()
  const canRead = hasPermission('AUTOMATION_READ')
  const canManage = hasPermission('AUTOMATION_MANAGE')
  const [taskFormOpen, setTaskFormOpen] = useState(false)
  const [editingTask, setEditingTask] = useState<AiTask | null>(null)
  const [taskForm, setTaskForm] = useState<AiTaskForm>(defaultTaskForm())
  const tasks = useQuery({ queryKey: ['ai-tasks'], queryFn: () => apiRequest<AiTask[]>('/api/automation/tasks'), enabled: canRead, refetchInterval: 15_000 })
  const taskRuns = useQuery({ queryKey: ['ai-task-runs'], queryFn: () => apiRequest<AiTaskRun[]>('/api/automation/task-runs?limit=100'), enabled: canRead, refetchInterval: 5_000 })
  const saveTask = useMutation({ mutationFn: (value: AiTaskForm) => apiRequest<AiTask>(editingTask ? `/api/automation/tasks/${editingTask.id}` : '/api/automation/tasks', { method: editingTask ? 'PUT' : 'POST', body: JSON.stringify(value) }), onSuccess: () => { setTaskFormOpen(false); setEditingTask(null); queryClient.invalidateQueries({ queryKey: ['ai-tasks'] }) } })
  const runTask = useMutation({ mutationFn: (taskId: string) => apiRequest<{ runId: string }>(`/api/automation/tasks/${taskId}/run`, { method: 'POST' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai-task-runs'] }) })
  const deleteTask = useMutation({ mutationFn: (taskId: string) => apiRequest<void>(`/api/automation/tasks/${taskId}`, { method: 'DELETE' }), onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['ai-tasks'] }); queryClient.invalidateQueries({ queryKey: ['ai-task-runs'] }) } })

  /** 打开新建表单并恢复稳定的默认调度参数。 */
  function openCreate() {
    setEditingTask(null)
    setTaskForm(defaultTaskForm())
    setTaskFormOpen(true)
  }

  /** 打开编辑表单，将 API 返回的任务转换成控件所需字段。 */
  function openEdit(task: AiTask) {
    setEditingTask(task)
    setTaskForm({ name: task.name, prompt: task.prompt, taskType: task.taskType, collectionWindowDays: task.collectionWindowDays, frequency: task.frequency, runTime: task.runTime, dayOfWeek: task.dayOfWeek ?? 1, intervalMinutes: task.intervalMinutes ?? 60, enabled: task.enabled })
    setTaskFormOpen(true)
  }

  return <div className="standard-page evolution-page ai-tasks-page">
    <PageHeader title="AI 定时任务" actions={<button className="icon-button" aria-label="刷新 AI 定时任务" onClick={() => { tasks.refetch(); taskRuns.refetch() }}><RefreshCw size={16} /></button>} />
    <section className="panel ai-task-panel">
      <div className="panel-header"><div><h2><BrainCircuit size={18} />任务配置</h2><p>按计划查询大模型并生成资料来源，结果会进入知识编译和审核流程。</p></div>{canManage ? <button className="button primary" onClick={openCreate}><Plus size={15} />新建任务</button> : null}</div>
      {saveTask.error ? <Notice kind="error">{saveTask.error instanceof ApiError ? saveTask.error.message : 'AI 定时任务保存失败'}</Notice> : null}
      {runTask.error ? <Notice kind="error">{runTask.error instanceof ApiError ? runTask.error.message : 'AI 定时任务排队失败'}</Notice> : null}
      {deleteTask.error ? <Notice kind="error">{deleteTask.error instanceof ApiError ? deleteTask.error.message : 'AI 定时任务删除失败'}</Notice> : null}
      <div className="ai-task-list">{tasks.data?.map((task) => <article className={`ai-task-row ${task.enabled ? '' : 'disabled'}`} key={task.id}><div className="ai-task-title"><span className="policy-icon"><BrainCircuit size={16} /></span><div><strong>{task.name}</strong><small>{task.taskType === 'AI_VENDOR_NEWS' ? `官方资讯采集 · 最近 ${task.collectionWindowDays} 天` : task.prompt}</small></div></div><div className="ai-task-meta"><span>{taskScheduleLabel(task)}</span><span>下次：{timeUntil(task.nextRunAt)}</span></div><Badge value={task.enabled ? 'ENABLED' : 'DISABLED'} /><div className="ai-task-actions">{canManage ? <><button className="icon-button" title={task.enabled ? '停用' : '启用'} onClick={() => saveTask.mutate({ name: task.name, prompt: task.prompt, taskType: task.taskType, collectionWindowDays: task.collectionWindowDays, frequency: task.frequency, runTime: task.runTime, dayOfWeek: task.dayOfWeek ?? 1, intervalMinutes: task.intervalMinutes ?? 60, enabled: !task.enabled })}>{task.enabled ? <Pause size={15} /> : <Play size={15} />}</button><button className="icon-button" title="立即运行" onClick={() => runTask.mutate(task.id)}><Play size={15} /></button><button className="icon-button" title="编辑" onClick={() => openEdit(task)}><Save size={15} /></button><button className="icon-button danger" title="删除" onClick={() => { if (window.confirm('确定删除这个定时任务及其记录吗？')) deleteTask.mutate(task.id) }}><Trash2 size={15} /></button></> : null}</div></article>)}{!tasks.isLoading && !tasks.data?.length ? <EmptyState icon={<BrainCircuit size={24} />} title="还没有 AI 定时任务" description="创建一个任务，让大模型按计划整理最新资料。" /> : null}</div>
    </section>
    <section className="panel ai-task-runs"><div className="panel-header"><div><h2><History size={18} />处理记录</h2><p>记录模型调用、生成来源和知识编译结果。</p></div></div><div className="ai-task-run-list">{taskRuns.data?.map((run) => <article key={run.id} className="ai-task-run-row"><div><strong>{run.taskName}</strong><small>{run.triggerType === 'MANUAL' ? '手动执行' : '定时执行'} · {timeAgo(run.createdAt)}</small></div><Badge value={run.status} /><span className="ai-task-run-model">{run.modelName ? `${run.modelProvider ?? ''} / ${run.modelName}` : '未调用模型'}</span>{run.collectionDetails?.captured !== undefined ? <small className="muted-box">{run.collectionDetails.fromDate} 至 {run.collectionDetails.toDate} · 成功采集 {run.collectionDetails.captured} 个官方页面 · 失败 {run.collectionDetails.failed ?? 0} 个</small> : null}<p>{friendlyTaskRunMessage(run)}</p>{run.generatedSourceId ? <small className="muted-box">已生成资料来源，摄取任务：{run.ingestionJobId}</small> : null}</article>)}{!taskRuns.isLoading && !taskRuns.data?.length ? <EmptyState icon={<History size={24} />} title="暂无处理记录" /> : null}</div></section>
    {taskFormOpen ? <Modal title={editingTask ? '编辑 AI 定时任务' : '新建 AI 定时任务'} wide onClose={() => { setTaskFormOpen(false); setEditingTask(null) }}><form className="ai-task-form" onSubmit={(event) => { event.preventDefault(); saveTask.mutate(taskForm) }}><div className="ai-task-form-grid"><label>任务类型<select value={taskForm.taskType} onChange={(event) => { const taskType = event.target.value as AiTaskForm['taskType']; setTaskForm({ ...taskForm, taskType, name: taskType === 'AI_VENDOR_NEWS' ? '主流 AI 厂商每日资讯' : taskForm.name, prompt: taskType === 'AI_VENDOR_NEWS' ? '整理主流 AI 厂商的新模型版本、产品变化与重要官方消息，并关联到对应厂商和模型。' : taskForm.prompt }) }}><option value="AI_VENDOR_NEWS">主流 AI 厂商官方资讯</option><option value="GENERIC">通用大模型任务</option></select></label>{taskForm.taskType === 'AI_VENDOR_NEWS' ? <label>资讯范围<select value={taskForm.collectionWindowDays} onChange={(event) => setTaskForm({ ...taskForm, collectionWindowDays: Number(event.target.value) })}><option value={7}>最近 7 天</option><option value={31}>最近 31 天</option><option value={90}>最近 90 天</option></select></label> : null}</div><label>任务名称<input required value={taskForm.name} onChange={(event) => setTaskForm({ ...taskForm, name: event.target.value })} placeholder="例如：主流 AI 厂商每日资讯" /></label><label>处理要求<textarea required rows={4} value={taskForm.prompt} onChange={(event) => setTaskForm({ ...taskForm, prompt: event.target.value })} /></label><div className="ai-task-form-grid"><label>频率<select value={taskForm.frequency} onChange={(event) => setTaskForm({ ...taskForm, frequency: event.target.value as AiTaskForm['frequency'] })}><option value="DAILY">每天</option><option value="WEEKLY">每周</option><option value="INTERVAL">按间隔</option></select></label>{taskForm.frequency !== 'INTERVAL' ? <label>执行时间<input type="time" value={taskForm.runTime} onChange={(event) => setTaskForm({ ...taskForm, runTime: event.target.value })} /></label> : <label>间隔<select value={taskForm.intervalMinutes} onChange={(event) => setTaskForm({ ...taskForm, intervalMinutes: Number(event.target.value) })}><option value={30}>每 30 分钟</option><option value={60}>每小时</option><option value={360}>每 6 小时</option><option value={1440}>每天</option></select></label>}{taskForm.frequency === 'WEEKLY' ? <label>星期<select value={taskForm.dayOfWeek} onChange={(event) => setTaskForm({ ...taskForm, dayOfWeek: Number(event.target.value) })}>{['一','二','三','四','五','六','日'].map((day, index) => <option value={index + 1} key={day}>星期{day}</option>)}</select></label> : null}</div><label className="checkbox-line"><input type="checkbox" checked={taskForm.enabled} onChange={(event) => setTaskForm({ ...taskForm, enabled: event.target.checked })} />保存后启用</label><div className="modal-actions"><button type="button" className="button ghost" onClick={() => setTaskFormOpen(false)}>取消</button><button className="button primary" disabled={saveTask.isPending} type="submit">{saveTask.isPending ? '保存中…' : '保存任务'}</button></div></form></Modal> : null}
  </div>
}

/** 创建符合后台默认约束的新任务表单。 */
function defaultTaskForm(): AiTaskForm { return { name: '主流 AI 厂商每日资讯', prompt: '整理主流 AI 厂商的新模型版本、产品变化与重要官方消息，并关联到对应厂商和模型。', taskType: 'AI_VENDOR_NEWS', collectionWindowDays: 31, frequency: 'DAILY', runTime: '12:00', dayOfWeek: 1, intervalMinutes: 60, enabled: true } }

/** 将任务频率转换为用户可理解的中文执行计划。 */
function taskScheduleLabel(task: AiTask) {
  if (task.frequency === 'INTERVAL') return `每 ${task.intervalMinutes ?? 60} 分钟`
  if (task.frequency === 'WEEKLY') return `每周${['一','二','三','四','五','六','日'][(task.dayOfWeek ?? 1) - 1]} ${task.runTime}`
  return `每天 ${task.runTime}`
}

/** 把定时任务的额度、密钥和网络错误转成可执行的中文提示。 */
function friendlyTaskRunMessage(run: AiTaskRun) {
  const message = run.errorMessage || run.resultSummary || '等待后台处理…'
  if (message.includes('额度') || message.includes('使用上限') || message.includes('429')) return `模型额度已达到服务商限制：${message}`
  if (message.includes('decrypt') || message.includes('密钥')) return `模型配置不可用：${message}`
  return message
}
