-- 后台调度需要跨租户扫描“到期任务”，但实际生成资料仍在租户上下文中完成。
-- 仅允许后端事务显式设置 app.scheduler_mode，普通请求不会获得跨租户可见性。
drop policy if exists ai_scheduled_tasks_tenant_policy on ai_scheduled_tasks;
create policy ai_scheduled_tasks_tenant_policy on ai_scheduled_tasks for all
    using ((organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
           or current_setting('app.scheduler_mode', true) = 'true')
    with check ((organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
                or current_setting('app.scheduler_mode', true) = 'true');

drop policy if exists ai_scheduled_task_runs_tenant_policy on ai_scheduled_task_runs;
create policy ai_scheduled_task_runs_tenant_policy on ai_scheduled_task_runs for all
    using ((organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
           or current_setting('app.scheduler_mode', true) = 'true')
    with check ((organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
                or current_setting('app.scheduler_mode', true) = 'true');
