/** 当前登录用户和租户上下文。 */
export interface CurrentUser {
  id: string
  email: string
  displayName: string
  organizationId: string
  organizationName: string
  workspaceId: string
  workspaceName: string
  permissions: string[]
}

/** 登录/刷新/空间切换的响应。 */
export interface AuthResponse {
  accessToken: string
  user: CurrentUser
}

/** API 标准错误载荷。 */
export interface ApiErrorPayload {
  status: number
  code: string
  message: string
}

/** 页面列表项。 */
export interface PageSummary {
  id: string
  slug: string
  title: string
  pageType: string
  revisionNo: number
  excerpt: string
  updatedAt: string
}

/** 页面详情。 */
export interface PageDetail extends PageSummary {
  currentRevisionId: string
  contentMarkdown: string
  createdAt: string
  evidence: Array<{
    id: string
    sourceId: string
    sourceTitle: string
    sourceType: string
    canonicalUri?: string
    quote: string
    locator: string
  }>
  backlinks: Array<{ pageId: string; slug: string; title: string; relationType: string }>
  relatedPages: Array<{ pageId: string; slug: string; title: string; pageType: string; relationType: string }>
  history: Array<{
    changeSetId: string
    title: string
    summary: string
    status: string
    changeType: string
    proposedBy: string
    reviewer?: string
    decision?: string
    comment?: string
    createdAt: string
    resolvedAt?: string
  }>
}

/** 审核队列项。 */
export interface ReviewSummary {
  id: string
  title: string
  summary: string
  status: string
  sourceTitle: string
  changeType: string
  proposedBy: string
  actionCount: number
  updateCount: number
  createdAt: string
}

/** 审核详情。 */
export interface ReviewDetail extends ReviewSummary {
  sourceId?: string
  resolvedAt?: string
  actions: Array<{
    id: string
    actionType: string
    pageId?: string
    baseRevisionId?: string
    proposedSlug: string
    proposedTitle: string
    proposedPageType: string
    baseTitle: string
    baseContentMarkdown: string
    proposedContentMarkdown: string
  }>
}
