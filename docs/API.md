# Enterprise LLM Wiki 接口文档

本文档描述当前 Java 服务公开的 REST API 与 MCP 接口。默认服务地址：

```text
http://127.0.0.1:8123
```

## 1. 认证约定

除登录和刷新外，REST 请求使用 Bearer JWT：

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

MCP 也可以使用绑定用户、组织和空间的 `lwk_` API Key：

```http
Authorization: Bearer lwk_xxx
MCP-Protocol-Version: 2025-03-26
```

系统不会只相信 JWT 中的权限；每次请求都会重新校验数据库会话、成员状态、角色和权限。

## 2. 统一错误响应

```json
{
  "timestamp": "2026-08-10T08:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "请求参数不合法"
}
```

## 3. 认证与空间

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录并返回访问令牌，刷新令牌写入 HttpOnly Cookie |
| POST | `/api/auth/refresh` | 刷新 Cookie | 轮换刷新令牌并签发新访问令牌 |
| POST | `/api/auth/logout` | 已登录 | 撤销当前会话 |
| GET | `/api/auth/me` | 已登录 | 获取当前用户、组织、空间和权限 |
| GET | `/api/auth/workspaces` | 已登录 | 获取可访问的组织与空间 |
| POST | `/api/auth/switch-workspace` | 已登录 | 切换当前空间并返回新令牌 |
| POST | `/api/auth/api-keys` | `MCP_USE` | 创建 MCP API Key，原始值只返回一次 |
| GET | `/api/auth/api-keys` | `MCP_USE` | 列出 API Key 元数据 |
| POST | `/api/auth/api-keys/{keyId}/revoke` | `MCP_USE` | 撤销 API Key |

登录示例：

```http
POST /api/auth/login
Content-Type: application/json

{
  "identifier": "1",
  "password": "1"
}
```

## 4. 成员、角色与组织

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/admin/members` | `MEMBER_MANAGE` | 列出当前空间成员 |
| POST | `/api/admin/members` | `MEMBER_MANAGE` | 添加或加入成员 |
| PUT | `/api/admin/members/{userId}/roles` | `ROLE_MANAGE` | 替换成员角色 |
| GET | `/api/admin/roles` | `ROLE_MANAGE` | 列出系统与自定义角色 |
| GET | `/api/admin/permissions` | `ROLE_MANAGE` | 列出权限定义 |
| POST | `/api/admin/roles` | `ROLE_MANAGE` | 创建自定义角色 |
| PUT | `/api/admin/roles/{roleId}` | `ROLE_MANAGE` | 更新自定义角色 |
| POST | `/api/admin/workspaces` | `MEMBER_MANAGE` | 在当前组织创建空间 |

## 5. 资料来源

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/sources` | `SOURCE_CREATE` | 创建文本或网页来源并排队编译 |
| POST | `/api/sources/upload` | `SOURCE_CREATE` | Multipart 文件上传 |
| GET | `/api/sources?limit=50` | `SOURCE_READ` | 列出当前空间来源 |
| GET | `/api/sources/{sourceId}/download` | `SOURCE_READ` | 下载原始文件并保留正确扩展名 |
| POST | `/api/sources/{sourceId}/refresh` | `SOURCE_CREATE` | 重新提取并编译来源 |

创建文本来源：

```http
POST /api/sources

{
  "sourceType": "TEXT",
  "title": "模型版本说明",
  "text": "正文内容",
  "uri": null
}
```

上传文件：

```bash
curl -X POST "http://127.0.0.1:8123/api/sources/upload" \
  -H "Authorization: Bearer <access-token>" \
  -F "file=@example.docx" \
  -F "title=产品白皮书" \
  -F "sourceType=FILE"
```

## 6. Wiki 页面与知识关系

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/pages` | `PAGE_READ` | 游标分页列出已发布页面 |
| GET | `/api/pages/{pageId}` | `PAGE_READ` | 获取页面、来源证据、反向链接和关联知识 |
| POST | `/api/pages` | `PAGE_CREATE` | 创建页面或提交完整页面修改提案 |
| POST | `/api/pages/{pageId}/archive` | `PAGE_UPDATE_PROPOSE` | 提交删除知识的审核单 |
| POST | `/api/pages/{pageId}/relations/{relatedPageId}/remove` | `PAGE_UPDATE_PROPOSE` | 提交删除关联知识的审核单 |
| GET | `/api/graph` | `PAGE_READ` | 获取当前空间知识图谱快照 |

页面变更示例：

```json
{
  "pageId": null,
  "slug": "enterprise-llm-wiki",
  "title": "Enterprise LLM Wiki",
  "pageType": "TOPIC",
  "contentMarkdown": "# Enterprise LLM Wiki\n\n正文"
}
```

`pageId` 为空表示新建，传入已有页面 ID 表示提交修改。已有页面更新不会直接覆盖发布版本。

## 7. 问 Wiki 与历史会话

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/query` | `QUERY_EXECUTE` | 执行一次带引用的同步查询 |
| GET | `/api/query/model-status` | `QUERY_EXECUTE` | 获取模型是否可用及不可用原因 |
| GET | `/api/query/conversations` | `QUERY_EXECUTE` | 列出当前用户历史会话 |
| POST | `/api/query/conversations` | `QUERY_EXECUTE` | 创建会话并异步提交首个问题 |
| GET | `/api/query/conversations/{conversationId}` | `QUERY_EXECUTE` | 获取会话及持久化消息 |
| POST | `/api/query/conversations/{conversationId}/messages` | `QUERY_EXECUTE` | 追加问题 |
| PATCH | `/api/query/conversations/{conversationId}` | `QUERY_EXECUTE` | 重命名或置顶会话 |
| DELETE | `/api/query/conversations/{conversationId}` | `QUERY_EXECUTE` | 删除会话及消息 |

```json
{
  "question": "当前知识库有哪些模型版本信息？",
  "useModel": true
}
```

关闭 `useModel` 时仅执行 Wiki 检索和引用组织，不调用外部模型。

## 8. 审核中心

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/reviews?limit=50&pageId={pageId}` | `REVIEW_READ` | 获取待审核变更，可按页面筛选 |
| GET | `/api/reviews/{changeSetId}` | `REVIEW_READ` | 获取前后文本与变更详情 |
| POST | `/api/reviews/{changeSetId}/approve` | `REVIEW_APPROVE` | 批准并发布不可变修订 |
| POST | `/api/reviews/{changeSetId}/reject` | `REVIEW_APPROVE` | 驳回并保留审计记录 |

```json
{
  "comment": "资料与页面证据一致，同意发布。"
}
```

## 9. 模型、持续优化与 AI 定时任务

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/automation/model` | `MODEL_MANAGE` | 获取脱敏模型配置 |
| POST | `/api/automation/model/test` | `MODEL_MANAGE` | 测试模型连接；测试失败的配置不能保存 |
| PUT | `/api/automation/model` | `MODEL_MANAGE` | 保存已通过测试的模型配置 |
| GET | `/api/automation/settings` | `AUTOMATION_READ` | 获取网页刷新和持续优化设置 |
| PUT | `/api/automation/settings` | `AUTOMATION_MANAGE` | 更新自动化设置 |
| GET | `/api/automation/runs` | `AUTOMATION_READ` | 获取持续优化记录 |
| POST | `/api/automation/runs` | `AUTOMATION_MANAGE` | 立即执行持续优化 |
| GET | `/api/automation/tasks` | `AUTOMATION_READ` | 列出 AI 定时任务 |
| POST | `/api/automation/tasks` | `AUTOMATION_MANAGE` | 创建 AI 定时任务 |
| PUT | `/api/automation/tasks/{taskId}` | `AUTOMATION_MANAGE` | 更新、启用或停用任务 |
| DELETE | `/api/automation/tasks/{taskId}` | `AUTOMATION_MANAGE` | 删除任务及其执行记录 |
| POST | `/api/automation/tasks/{taskId}/run` | `AUTOMATION_MANAGE` | 立即排队执行任务 |
| GET | `/api/automation/task-runs` | `AUTOMATION_READ` | 获取任务状态、模型和处理结果 |

AI 定时任务示例：

```json
{
  "name": "跟踪大模型版本",
  "prompt": "查询大模型最新版本、发布日期和主要变化，整理成事实性 Markdown。",
  "frequency": "DAILY",
  "runTime": "12:00",
  "dayOfWeek": null,
  "intervalMinutes": null,
  "enabled": true
}
```

`frequency` 支持：

- `DAILY`：每天在 `runTime` 执行
- `WEEKLY`：每周在 `dayOfWeek`（1-7）和 `runTime` 执行
- `INTERVAL`：每隔 `intervalMinutes` 执行，范围 30-10080 分钟

任务输出会生成可追溯的 TEXT 来源并进入现有摄取、编译和审核链路。

## 10. 导出

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/export/obsidian` | `EXPORT_WIKI` | 流式下载 Obsidian 兼容 ZIP |

导出包含 Markdown、WikiLink、YAML frontmatter、稳定页面 ID、来源信息和 `.obsidian` 配置。

## 11. MCP Streamable HTTP

端点：

```text
POST /mcp
```

支持的方法：

- `initialize`
- `ping`
- `tools/list`
- `tools/call`

工具列表：

| 工具 | 需要权限 | 说明 |
|---|---|---|
| `llm_wiki_query` | `QUERY_EXECUTE` | 查询已编译 Wiki 并返回修订级引用 |
| `llm_wiki_list_pages` | `PAGE_READ` | 列出当前空间发布页面 |
| `llm_wiki_propose_page` | `PAGE_CREATE` | 新建页面或提交页面修改提案 |
| `llm_wiki_add_text_source` | `SOURCE_CREATE` | 添加不可变文本来源并排队编译 |
| `llm_wiki_review_change` | `REVIEW_APPROVE` | 批准或驳回变更集 |

初始化示例：

```http
POST /mcp
Authorization: Bearer lwk_xxx
MCP-Protocol-Version: 2025-03-26
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": {
      "name": "example-client",
      "version": "1.0.0"
    }
  }
}
```
