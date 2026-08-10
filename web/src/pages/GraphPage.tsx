import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, BookOpen, Network, Search, Settings2, X } from 'lucide-react'
import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Badge, EmptyState, LoadingBlock, Modal, PageHeader } from '../components/Ui'
import { apiRequest } from '../lib/api'
import type { PageDetail } from '../types'

interface GraphNode { id: string; slug: string; title: string; pageType: string; revisionNo: number }
interface GraphEdge { id: string; fromPageId: string; toPageId: string; relationType: string }
interface GraphSnapshot { nodes: GraphNode[]; edges: GraphEdge[] }
interface ForceNode extends GraphNode { x: number; y: number; vx: number; vy: number; radius: number }
interface LayoutSettings { linkDistance: number; repulsion: number; nodeScale: number }

const graphTypes = [
  { value: 'TOPIC', label: '主题' }, { value: 'ENTITY', label: '实体' },
  { value: 'SYNTHESIS', label: '综合' }, { value: 'CONTEXT', label: '上下文' },
  { value: 'README', label: '说明' },
]

/** Obsidian 风格的交互式知识图谱，支持聚焦、筛选、力导向拖拽和弹窗内连续浏览。 */
export default function GraphPage() {
  const graph = useQuery({ queryKey: ['graph'], queryFn: () => apiRequest<GraphSnapshot>('/api/graph') })
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search)
  const [types, setTypes] = useState(() => new Set(graphTypes.map((item) => item.value)))
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const [detailHistory, setDetailHistory] = useState<string[]>([])
  const [focusNodeId, setFocusNodeId] = useState<string | null>(null)
  const [hoverNodeId, setHoverNodeId] = useState<string | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [showLabels, setShowLabels] = useState(true)
  const [showRelations, setShowRelations] = useState(false)
  const [hideOrphans, setHideOrphans] = useState(false)
  const [showArrows, setShowArrows] = useState(false)
  const [layout, setLayout] = useState<LayoutSettings>({ linkDistance: 165, repulsion: 1100, nodeScale: 1 })
  const svgRef = useRef<SVGSVGElement>(null)
  const stageRef = useRef<HTMLDivElement>(null)
  const panDrag = useRef<{ x: number; y: number } | null>(null)
  const nodeMoved = useRef(false)
  const filtered = useMemo(() => filterSnapshot(graph.data, deferredSearch, types, hideOrphans),
    [graph.data, deferredSearch, types, hideOrphans])
  const { nodes, edges, beginNodeDrag, moveNode, endNodeDrag } = useForceLayout(filtered, layout)
  const currentDetailId = detailHistory.at(-1)
  const activeNodeId = hoverNodeId
  const connectedIds = useMemo(() => connectionSet(filtered, activeNodeId), [filtered, activeNodeId])

  /** 使用非 passive 原生监听拦截画布滚轮，避免页面跟随滚动并保证缩放在 Chrome 中生效。 */
  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return
    const handleWheel = (event: WheelEvent) => {
      event.preventDefault()
      event.stopPropagation()
      setZoom((value) => Math.max(.3, Math.min(3.5, value * Math.exp(-event.deltaY * .0012))))
    }
    stage.addEventListener('wheel', handleWheel, { passive: false })
    return () => stage.removeEventListener('wheel', handleWheel)
  }, [nodes.length])

  /** 切换页面类型筛选，并至少保留一种类型，避免误操作得到无法恢复的空视图。 */
  function toggleType(type: string) {
    setTypes((current) => {
      const next = new Set(current)
      if (next.has(type) && next.size > 1) next.delete(type)
      else next.add(type)
      return next
    })
  }

  /** 将屏幕位移换算为 SVG 坐标，保证缩放和不同窗口宽度下拖拽速度一致。 */
  function graphDelta(value: number) {
    const width = svgRef.current?.getBoundingClientRect().width ?? 1000
    return value * 1000 / Math.max(1, width)
  }

  /** 打开节点详情并记录最近焦点，局部图模式会以该节点为中心。 */
  function openNode(nodeId: string) {
    setFocusNodeId(nodeId)
    setDetailHistory([nodeId])
  }

  return <div className="standard-page graph-page">
    <PageHeader title="知识图谱" />
    <section className="graph-canvas panel">
      <div className="graph-toolbar">
        <label className="graph-search"><Search size={14} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索知识节点" />{search ? <button onClick={() => setSearch('')} aria-label="清除搜索"><X size={12} /></button> : null}</label>
        <div className="graph-type-filters">{graphTypes.map((item) => <button key={item.value} className={types.has(item.value) ? `active type-${item.value.toLowerCase()}` : ''} onClick={() => toggleType(item.value)}><i />{item.label}</button>)}</div>
        <span className="graph-count">{nodes.length} 节点 · {edges.length} 关系</span>
        <button className={`graph-settings-button ${showSettings ? 'active' : ''}`} onClick={() => setShowSettings((value) => !value)} aria-label="图谱设置"><Settings2 size={15} /></button>
      </div>
      {graph.isLoading ? <LoadingBlock rows={7} /> : nodes.length ? <div ref={stageRef} className="graph-stage">
        <svg ref={svgRef} viewBox="0 0 1000 640" onPointerDown={(event) => { if ((event.target as Element).closest('.graph-node')) return; event.currentTarget.setPointerCapture(event.pointerId); panDrag.current = { x: event.clientX, y: event.clientY } }} onPointerMove={(event) => { if (!panDrag.current) return; const dx = graphDelta(event.clientX - panDrag.current.x); const dy = graphDelta(event.clientY - panDrag.current.y); panDrag.current = { x: event.clientX, y: event.clientY }; setPan((value) => ({ x: value.x + dx, y: value.y + dy })) }} onPointerUp={(event) => { panDrag.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId) }} onPointerCancel={() => { panDrag.current = null }}>
          <defs>
            <filter id="node-glow"><feDropShadow dx="0" dy="1" stdDeviation="3" floodColor="#385b8e" floodOpacity=".26" /></filter>
            <marker id="graph-arrow" markerWidth="7" markerHeight="7" refX="7" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7 Z" /></marker>
            <pattern id="graph-grid" width="34" height="34" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".65" /></pattern>
          </defs>
          <rect className="graph-grid" width="1000" height="640" />
          <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
            <g className="graph-edges">{edges.map((edge) => {
              const dimmed = activeNodeId && edge.fromPageId !== activeNodeId && edge.toPageId !== activeNodeId
              const middleX = (edge.from.x + edge.to.x) / 2
              const middleY = (edge.from.y + edge.to.y) / 2
              return <g key={edge.id} className={`graph-edge relation-${edge.relationType.toLowerCase()} ${dimmed ? 'dimmed' : 'connected'}`}>
                <line x1={edge.from.x} y1={edge.from.y} x2={edge.to.x} y2={edge.to.y} markerEnd={showArrows ? 'url(#graph-arrow)' : undefined} />
                {showRelations && !dimmed ? <text x={middleX} y={middleY - 4} textAnchor="middle">{relationLabel(edge.relationType)}</text> : null}
              </g>
            })}</g>
            <g>{nodes.map((node) => {
              const dimmed = activeNodeId !== null && !connectedIds.has(node.id)
              const active = node.id === activeNodeId || node.id === focusNodeId
              return <g key={node.id} role="button" tabIndex={0} aria-label={`打开 ${node.title}`} className={`graph-node graph-${node.pageType.toLowerCase()} ${dimmed ? 'dimmed' : ''} ${active ? 'active' : ''}`} transform={`translate(${node.x} ${node.y})`} onClick={() => { if (!nodeMoved.current) openNode(node.id) }} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openNode(node.id) } }} onPointerEnter={() => setHoverNodeId(node.id)} onPointerLeave={() => setHoverNodeId(null)} onPointerDown={(event) => { event.stopPropagation(); event.currentTarget.setPointerCapture(event.pointerId); nodeMoved.current = false; beginNodeDrag(node.id) }} onPointerMove={(event) => { if (!event.currentTarget.hasPointerCapture(event.pointerId)) return; if (Math.abs(event.movementX) + Math.abs(event.movementY) > 1) nodeMoved.current = true; moveNode(node.id, graphDelta(event.movementX) / zoom, graphDelta(event.movementY) / zoom) }} onPointerUp={(event) => { if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); endNodeDrag() }} onPointerCancel={endNodeDrag}>
                <circle r={node.radius} filter={active ? 'url(#node-glow)' : undefined} />
                {showLabels ? <text textAnchor="middle" y={node.radius + 16}>{truncate(node.title, 20)}</text> : null}
                <title>{node.title}</title>
              </g>
            })}</g>
          </g>
        </svg>
        <div className="graph-legend">{graphTypes.slice(0, 4).map((type) => <span key={type.value} className={`legend-${type.value.toLowerCase()}`}><i />{type.label}</span>)}</div>
        {showSettings ? <GraphSettings showLabels={showLabels} setShowLabels={setShowLabels} showRelations={showRelations} setShowRelations={setShowRelations} hideOrphans={hideOrphans} setHideOrphans={setHideOrphans} showArrows={showArrows} setShowArrows={setShowArrows} layout={layout} setLayout={setLayout} onClose={() => setShowSettings(false)} /> : null}
      </div> : <EmptyState icon={<Network size={27} />} title="没有匹配的知识关系" />}
    </section>
    {currentDetailId ? <GraphDetailModal nodeId={currentDetailId} snapshot={graph.data} canBack={detailHistory.length > 1} onBack={() => setDetailHistory((value) => value.slice(0, -1))} onNavigate={(id) => { setFocusNodeId(id); setDetailHistory((value) => value.at(-1) === id ? value : [...value, id]) }} onClose={() => setDetailHistory([])} /> : null}
  </div>
}

/** 提供可即时生效的图谱显示和力导向参数，保持主画布工具栏简洁。 */
function GraphSettings({ showLabels, setShowLabels, showRelations, setShowRelations, hideOrphans, setHideOrphans, showArrows, setShowArrows, layout, setLayout, onClose }: { showLabels: boolean; setShowLabels: (value: boolean) => void; showRelations: boolean; setShowRelations: (value: boolean) => void; hideOrphans: boolean; setHideOrphans: (value: boolean) => void; showArrows: boolean; setShowArrows: (value: boolean) => void; layout: LayoutSettings; setLayout: (value: LayoutSettings) => void; onClose: () => void }) {
  return <aside className="graph-settings-panel">
    <header><strong>图谱设置</strong><button onClick={onClose} aria-label="关闭设置"><X size={14} /></button></header>
    <section><h3>显示</h3><ToggleRow label="节点名称" checked={showLabels} onChange={setShowLabels} /><ToggleRow label="关系名称" checked={showRelations} onChange={setShowRelations} /><ToggleRow label="方向箭头" checked={showArrows} onChange={setShowArrows} /><ToggleRow label="隐藏孤立节点" checked={hideOrphans} onChange={setHideOrphans} /></section>
    <section><h3>布局</h3><RangeRow label="节点大小" value={layout.nodeScale} min={.7} max={1.5} step={.1} onChange={(nodeScale) => setLayout({ ...layout, nodeScale })} /><RangeRow label="连线距离" value={layout.linkDistance} min={80} max={210} step={5} onChange={(linkDistance) => setLayout({ ...layout, linkDistance })} /><RangeRow label="排斥强度" value={layout.repulsion} min={500} max={2200} step={100} onChange={(repulsion) => setLayout({ ...layout, repulsion })} /></section>
  </aside>
}

/** 设置面板中的紧凑布尔开关。 */
function ToggleRow({ label, checked, onChange }: { label: string; checked: boolean; onChange: (value: boolean) => void }) {
  return <label className="graph-toggle-row"><span>{label}</span><input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} /><i /></label>
}

/** 设置面板中的数值滑杆，数值变化会重新启动力导向模拟。 */
function RangeRow({ label, value, min, max, step, onChange }: { label: string; value: number; min: number; max: number; step: number; onChange: (value: number) => void }) {
  return <label className="graph-range-row"><span>{label}<small>{value}</small></span><input type="range" value={value} min={min} max={max} step={step} onChange={(event) => onChange(Number(event.target.value))} /></label>
}

/** 在弹窗内展示页面正文和关系；关系跳转压入本地历史，关闭前可连续返回。 */
function GraphDetailModal({ nodeId, snapshot, canBack, onBack, onNavigate, onClose }: { nodeId: string; snapshot?: GraphSnapshot; canBack: boolean; onBack: () => void; onNavigate: (id: string) => void; onClose: () => void }) {
  const detail = useQuery({ queryKey: ['graph-page-detail', nodeId], queryFn: () => apiRequest<PageDetail>(`/api/pages/${nodeId}`) })
  const neighbors = useMemo(() => graphNeighbors(snapshot, nodeId), [snapshot, nodeId])
  return <Modal title={detail.data?.title ?? '知识详情'} onClose={onClose} wide>
    <div className="graph-detail-toolbar"><button className="button ghost" disabled={!canBack} onClick={onBack}><ArrowLeft size={15} />返回</button>{detail.data ? <div><Badge value={detail.data.pageType} /></div> : null}</div>
    {detail.isLoading ? <LoadingBlock rows={8} /> : detail.data ? <div className="graph-detail-content">
      <article><div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{detail.data.contentMarkdown}</ReactMarkdown></div></article>
      <aside><h3><Network size={15} />关联知识</h3><div className="graph-neighbor-list">{neighbors.map((neighbor) => <button key={`${neighbor.id}-${neighbor.relationType}`} onClick={() => onNavigate(neighbor.id)}><span className={`page-type-icon type-${neighbor.pageType.toLowerCase()}`}><BookOpen size={14} /></span><span><strong>{neighbor.title}</strong><small>{relationLabel(neighbor.relationType)}</small></span></button>)}{!neighbors.length ? <p>暂无关联页面</p> : null}</div><h3>来源证据</h3><div className="graph-evidence-list">{detail.data.evidence.map((item) => <div key={item.id}><strong>{item.sourceTitle}</strong><small>{item.quote || '已关联来源'}</small></div>)}{!detail.data.evidence.length ? <p>暂无来源证据</p> : null}</div></aside>
    </div> : <EmptyState title="页面不存在" />}
  </Modal>
}

/** 按关键词、类型、孤立状态和局部焦点生成子图，边仅保留在可见节点之间。 */
function filterSnapshot(snapshot: GraphSnapshot | undefined, search: string, types: Set<string>, hideOrphans: boolean): GraphSnapshot {
  if (!snapshot) return { nodes: [], edges: [] }
  const query = search.trim().toLowerCase()
  const connected = new Set(snapshot.edges.flatMap((edge) => [edge.fromPageId, edge.toPageId]))
  const nodes = snapshot.nodes.filter((node) => types.has(node.pageType) && (!query || node.title.toLowerCase().includes(query)) && (!hideOrphans || connected.has(node.id))).slice(0, 160)
  const ids = new Set(nodes.map((node) => node.id))
  return { nodes, edges: snapshot.edges.filter((edge) => ids.has(edge.fromPageId) && ids.has(edge.toPageId)) }
}

/** 返回焦点节点及其一跳邻居，用于局部视图与悬停高亮。 */
function connectionSet(snapshot: GraphSnapshot, nodeId: string | null) {
  const ids = new Set<string>()
  if (!nodeId) return ids
  ids.add(nodeId)
  snapshot.edges.forEach((edge) => { if (edge.fromPageId === nodeId) ids.add(edge.toPageId); if (edge.toPageId === nodeId) ids.add(edge.fromPageId) })
  return ids
}

/** 运行轻量力导向模拟；节点拖拽期间锁定该节点，松开后继续收敛。 */
function useForceLayout(snapshot: GraphSnapshot, settings: LayoutSettings) {
  const nodeRef = useRef<ForceNode[]>([])
  const edgeRef = useRef<GraphEdge[]>([])
  const lockedNode = useRef<string | null>(null)
  const generation = useRef(0)
  const [nodes, setNodes] = useState<ForceNode[]>([])

  /** 启动力导向动画并在温度衰减后自动停止，避免后台持续占用浏览器资源。 */
  function simulate() {
    const currentGeneration = ++generation.current
    let frame = 0
    const tick = () => {
      if (currentGeneration !== generation.current || frame++ > 280) return
      const values = nodeRef.current
      const byId = new Map(values.map((node) => [node.id, node]))
      for (let i = 0; i < values.length; i++) for (let j = i + 1; j < values.length; j++) {
        const a = values[i]; const b = values[j]
        let dx = a.x - b.x; let dy = a.y - b.y
        let distance = Math.hypot(dx, dy)
        if (distance < .5) {
          // 重叠节点没有有效方向，使用节点下标生成稳定方向，避免随机抖动且保证必然分离。
          const angle = (i * 31 + j * 17) * .618
          dx = Math.cos(angle); dy = Math.sin(angle); distance = 1
        }
        // 使用归一化方向与受限斥力，近距离能分离，远距离又不会把节点推到画布边界。
        const force = Math.min(.28, settings.repulsion / Math.max(24, distance) * .018)
        const forceX = dx / distance * force; const forceY = dy / distance * force
        a.vx += forceX; a.vy += forceY; b.vx -= forceX; b.vy -= forceY
      }
      edgeRef.current.forEach((edge) => {
        const a = byId.get(edge.fromPageId); const b = byId.get(edge.toPageId)
        if (!a || !b) return
        const dx = b.x - a.x; const dy = b.y - a.y; const distance = Math.max(1, Math.hypot(dx, dy))
        // 弹簧力沿单位方向施加并限制幅度，长连线不会因距离平方效应造成边界间过冲。
        const force = Math.max(-.45, Math.min(.9, (distance - settings.linkDistance) * .006))
        const forceX = dx / distance * force; const forceY = dy / distance * force
        a.vx += forceX; a.vy += forceY; b.vx -= forceX; b.vy -= forceY
      })
      values.forEach((node) => {
        if (lockedNode.current === node.id) return
        node.vx += (500 - node.x) * .0014; node.vy += (310 - node.y) * .0014
        node.vx *= .86; node.vy *= .86
        node.x = Math.max(30, Math.min(970, node.x + node.vx)); node.y = Math.max(28, Math.min(610, node.y + node.vy))
      })
      if (frame % 2 === 0) setNodes(values.map((node) => ({ ...node })))
      requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  }

  useEffect(() => {
    const degree = new Map<string, number>()
    snapshot.edges.forEach((edge) => { degree.set(edge.fromPageId, (degree.get(edge.fromPageId) ?? 0) + 1); degree.set(edge.toPageId, (degree.get(edge.toPageId) ?? 0) + 1) })
    nodeRef.current = snapshot.nodes.map((node, index) => {
      const angle = index / Math.max(1, snapshot.nodes.length) * Math.PI * 2
      const ring = index === 0 ? 0 : 125 + (index % 4) * 42
      return { ...node, x: 500 + Math.cos(angle) * ring, y: 310 + Math.sin(angle) * ring, vx: 0, vy: 0, radius: (8 + Math.min(11, (degree.get(node.id) ?? 0) * 1.7)) * settings.nodeScale }
    })
    edgeRef.current = snapshot.edges
    setNodes(nodeRef.current.map((node) => ({ ...node })))
    simulate()
    return () => { generation.current++ }
    // simulate 只消费本次写入 ref 的快照和参数，避免把每帧状态加入依赖。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot, settings])

  const byId = new Map(nodes.map((node) => [node.id, node]))
  const edges = snapshot.edges.flatMap((edge) => { const from = byId.get(edge.fromPageId); const to = byId.get(edge.toPageId); return from && to ? [{ ...edge, from, to }] : [] })
  return { nodes, edges, beginNodeDrag: (id: string) => { lockedNode.current = id }, moveNode: (id: string, dx: number, dy: number) => { const node = nodeRef.current.find((item) => item.id === id); if (!node) return; node.x += dx; node.y += dy; node.vx = 0; node.vy = 0; setNodes(nodeRef.current.map((item) => ({ ...item }))) }, endNodeDrag: () => { lockedNode.current = null; simulate() } }
}

/** 返回某节点的双向邻居及关系名称。 */
function graphNeighbors(snapshot: GraphSnapshot | undefined, nodeId: string) {
  if (!snapshot) return []
  const nodes = new Map(snapshot.nodes.map((node) => [node.id, node]))
  return snapshot.edges.flatMap((edge) => { const otherId = edge.fromPageId === nodeId ? edge.toPageId : edge.toPageId === nodeId ? edge.fromPageId : null; const node = otherId ? nodes.get(otherId) : null; return node ? [{ ...node, relationType: edge.relationType }] : [] })
}

/** 将后端关系类型转换为简洁中文。 */
function relationLabel(value: string) {
  return ({ WIKILINK: 'Wiki 链接', WIKI_LINK: 'Wiki 链接', RELATED: '相关', CONTRADICTS: '矛盾', SUPPORTS: '支持' } as Record<string, string>)[value] ?? value
}

/** 限制图谱标签长度，完整标题仍通过 SVG title 和详情弹窗提供。 */
function truncate(value: string, max: number) { return value.length <= max ? value : `${value.slice(0, max)}…` }
