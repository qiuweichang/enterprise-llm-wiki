-- 可配置 AI 定时任务：任务定义与每次执行记录分离，便于审计、重试和暂停。
create table ai_scheduled_tasks (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    name text not null,
    prompt text not null,
    frequency text not null check (frequency in ('DAILY', 'WEEKLY', 'INTERVAL')),
    run_time time not null default '12:00:00',
    day_of_week smallint check (day_of_week between 1 and 7),
    interval_minutes integer check (interval_minutes between 30 and 10080),
    enabled boolean not null default true,
    next_run_at timestamptz not null,
    last_run_at timestamptz,
    created_by uuid not null references users(id),
    updated_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade,
    check ((frequency = 'WEEKLY' and day_of_week is not null) or frequency <> 'WEEKLY'),
    check ((frequency = 'INTERVAL' and interval_minutes is not null) or frequency <> 'INTERVAL')
);
create index ai_scheduled_tasks_due_idx on ai_scheduled_tasks (next_run_at) where enabled;

create table ai_scheduled_task_runs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    task_id uuid not null references ai_scheduled_tasks(id) on delete cascade,
    status text not null default 'PENDING' check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    trigger_type text not null check (trigger_type in ('SCHEDULED', 'MANUAL')),
    lease_owner text,
    lease_until timestamptz,
    model_provider text,
    model_name text,
    generated_source_id uuid references sources(id),
    ingestion_job_id uuid references ingestion_jobs(id),
    result_summary text,
    error_message text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index ai_scheduled_task_runs_tenant_idx on ai_scheduled_task_runs (organization_id, workspace_id, created_at desc);
create index ai_scheduled_task_runs_claim_idx on ai_scheduled_task_runs (created_at)
    where status = 'PENDING' or status = 'RUNNING';
create unique index ai_scheduled_task_runs_active_uq on ai_scheduled_task_runs (task_id)
    where status in ('PENDING', 'RUNNING');

alter table ai_scheduled_tasks enable row level security;
alter table ai_scheduled_tasks force row level security;
create policy ai_scheduled_tasks_tenant_policy on ai_scheduled_tasks for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());
alter table ai_scheduled_task_runs enable row level security;
alter table ai_scheduled_task_runs force row level security;
create policy ai_scheduled_task_runs_tenant_policy on ai_scheduled_task_runs for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());
grant select, insert, update, delete on ai_scheduled_tasks, ai_scheduled_task_runs to llm_wiki_app;
