-- 将“通用模型提示词任务”和“主流 AI 厂商官方资讯采集任务”分开，避免把模型自身记忆误当成联网资讯。
alter table ai_scheduled_tasks
    add column task_type text not null default 'GENERIC'
        check (task_type in ('GENERIC', 'AI_VENDOR_NEWS')),
    add column collection_window_days integer not null default 31
        check (collection_window_days between 1 and 365);

alter table ai_scheduled_task_runs
    add column collection_details jsonb not null default '{}'::jsonb;

create index ai_scheduled_tasks_type_idx
    on ai_scheduled_tasks (organization_id, workspace_id, task_type, enabled);
