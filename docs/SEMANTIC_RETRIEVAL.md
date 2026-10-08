# Query 语义混合检索

## 当前实现

本地 Python 使用 `fastembed==0.7.4` / `BAAI/bge-small-zh-v1.5`，输出 512 维单位向量。无需聊天模型 API Key，不消耗生成模型额度。模型首次启动下载到 `python-worker/models/`，后续复用缓存；离线部署需要提前准备该目录。安装依赖：在 `python-worker` 执行 `.venv\Scripts\python.exe -m pip install -e .`。

向量存在现有 PostgreSQL `semantic_chunks.embedding real[]` 中，无需安装 pgvector。查询执行精确余弦计算（单位向量点积），**不是 HNSW/ANN**。现阶段适合中小规模知识库；数据量增加后应做负载测试，再迁移 `vector(512)` + HNSW，并保留租户、版本过滤及召回率测试。

## 发布到索引

1. 原始资料仍按原有 Python 提取、AI 编译、审核流程处理。语义索引针对已发布 Wiki，不直接索引原始文件或待审核提案。
2. 新增发布、批准更新、归档等对 `wiki_pages` 的修改，在同一事务中通过触发器更新 `semantic_jobs`。迁移 V14 自动排队已有发布页面。
3. Worker 每秒轮询，每进程最多处理一页；每个空间在 RLS 上下文内通过 `FOR UPDATE SKIP LOCKED` 领取任务，领取事务随即结束。
4. 按段落分隔，长段落以 320 Unicode 码点分块、重叠 48 码点，加入最多 64 码点的标题。没有“只截整篇前几千字”的限制。
5. 每批最多 16 块调用 Python，ONNX 使用 2 条 CPU 线程、单模型串行推理。网络等待不持有数据库事务。
6. 每批续租。全部成功后以页面→任务的固定锁顺序，核对 `revision_id + generation + lease_token + lease_until`，原子替换全部块和 DONE 状态。过期任务、重建前的任务不能覆盖新结果。
7. 失败持久化原因，按指数退避自动重试；五次后 FAILED，可在界面重建。进程中断后，过期租约由下一实例重新领取。

知识发布与向量生成是**最终一致**，不是同步等待模型。发布后旧索引立即不再参与召回，新索引成功前该页面仍可通过关键词检索。

## Query 流程

问题/必要历史 → 规范化 → 空间缓存版本 → 问题向量 → 两路检索 → RRF → 一跳关联扩展 → 摘录或大模型生成 → 引用、会话和运行记录。

- 关键词路保留 PostgreSQL 全文、标题三元组及正文匹配，最多 8 页。
- 语义路只查询当前组织、空间、已发布状态、当前修订、当前 generation、DONE 任务且模型标识匹配的块；同页取最高分，默认最低余弦相似度 0.50，最多 8 页。
- RRF 使用 `1 / (60 + rank)` 融合两路排名，去重取前 8 页，再扩展最多 4 个图邻居。不再用最近发布的几页冒充检索命中。
- 长文档传递最高分命中块，避免回答器截取全文头部后丢失尾部证据。
- `useModel` 只控制答案生成，关闭聊天模型后仍可使用语义检索。
- 语义服务故障明确标记 DEGRADED，保留可读原因，退回关键词；无索引标记 INDEXING。不缓存这两类降级答案。
- 缓存键包含页面/索引数据库快照指纹、空间开关和阈值，发布、归档、重建、索引完成会使旧结果失效。Redis 是可选加速，不承担正确性判断。
- `retrievalMode`、`retrievalMessage` 随查询运行与会话消息持久化；刷新页面可查看原回答使用的检索方式。HYBRID 表示执行了两路检索，并不保证每次都有语义命中。

## 界面和接口

“模型与 MCP → 语义检索”提供启停、真实检索测试、索引记录和确认后重建。启用时后端会实际调用模型，失败不保存。当前固定中文模型，不能把任意聊天模型名称当成 Embedding 模型。

| 接口 | 权限 | 用途 |
|---|---|---|
| GET `/api/semantic` | QUERY_EXECUTE | 模型、维度、当前空间进度 |
| GET `/api/semantic/jobs` | MODEL_MANAGE | 最近 50 页状态、块数、重试数、失败原因 |
| PUT `/api/semantic` | MODEL_MANAGE | `{ "enabled": true, "minScore": 0.5 }` |
| POST `/api/semantic/test` | MODEL_MANAGE | `{ "question": "换一种说法的问题" }`，真实模型和混合召回 |
| POST `/api/semantic/rebuild` | MODEL_MANAGE | 重建当前空间，不修改知识正文 |

关闭语义检索同时暂停新索引任务领取；已经在执行的任务可能完成。记录面板展示每页最新状态，不是每次尝试的无限历史流水；详细执行轨迹在服务端 INFO/ERROR 日志。

## 测试

- `mvn -pl server test`：纯算法、真实 PostgreSQL 发布/审核/RLS/向量检索及故障编排。数据库集成测试显式设置 `LLM_WIKI_DB_PASSWORD` 时使用独立的 `llm_wiki_test`；否则使用 Testcontainers。必须先启动 Python 服务（8101）。
- Python：`python-worker/.venv/Scripts/python.exe -m pytest python-worker/tests`，包括真实中文语义近义匹配，不是随机/mock 向量。
- Web：`npm run build`、`npm test`。
- 桌面端到端：`npx playwright test e2e/semantic-flow.spec.ts`。可设置 `LLM_WIKI_E2E_URL` 使用非默认前端端口；测试会启停、重建当前空间的派生索引，并创建一条真实查询会话，不删除已有知识。

模型或分块策略正式升级时，必须同步升级 Java/Python `MODEL_ID` 并排队重建；禁止用同一个标识混存不同模型向量。当前未提供在线选择其他 Embedding 模型，也没有完成大规模并发性能基准。

模型实现参考：[FastEmbed 支持的模型](https://qdrant.github.io/fastembed/examples/Supported_Models/)。
