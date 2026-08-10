alter table workspace_automation_settings
    add column optimization_interval_minutes integer not null default 30
        check (optimization_interval_minutes between 30 and 10080),
    add column next_optimization_at timestamptz not null default now(),
    add column last_optimization_at timestamptz;

alter table workspace_automation_settings alter column maintenance_enabled set default false;
update workspace_automation_settings
set maintenance_enabled = false
where updated_by is null;

alter table wiki_pages add column last_evolution_scanned_at timestamptz;
create index wiki_pages_evolution_scan_idx
    on wiki_pages (organization_id, workspace_id, last_evolution_scanned_at nulls first, updated_at desc)
    where status = 'PUBLISHED';

create table workspace_model_settings (
    organization_id uuid not null,
    workspace_id uuid primary key,
    enabled boolean not null default false,
    provider text not null default 'OPENAI_COMPATIBLE',
    base_url text not null default '',
    model_name text not null default '',
    api_key_ciphertext text,
    inherit_environment_key boolean not null default false,
    api_key_hint text,
    temperature numeric(3,2) not null default 0.20 check (temperature between 0 and 2),
    max_tokens integer not null default 4096 check (max_tokens between 256 and 32768),
    updated_by uuid references users(id),
    updated_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);

create table wiki_evolution_runs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    trigger_type text not null check (trigger_type in ('SCHEDULED', 'MANUAL')),
    status text not null default 'PENDING'
        check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    requested_by uuid references users(id),
    lease_owner text,
    lease_until timestamptz,
    model_provider text,
    model_name text,
    pages_scanned integer not null default 0,
    contradictions_found integer not null default 0,
    proposals_created integer not null default 0,
    change_set_ids uuid[] not null default '{}',
    result_summary text,
    details jsonb not null default '{}'::jsonb,
    error_message text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);

create index wiki_evolution_runs_tenant_created_idx
    on wiki_evolution_runs (organization_id, workspace_id, created_at desc, id);
create index wiki_evolution_runs_claim_idx
    on wiki_evolution_runs (created_at)
    where status = 'PENDING' or status = 'RUNNING';
create unique index wiki_evolution_runs_workspace_active_uq
    on wiki_evolution_runs (workspace_id)
    where status in ('PENDING', 'RUNNING');

insert into permissions(code, description) values
    ('AUTOMATION_READ', 'Read background evolution settings and run history'),
    ('AUTOMATION_MANAGE', 'Enable, disable and manually trigger background evolution'),
    ('MODEL_MANAGE', 'Configure the workspace AI model and encrypted credential');

insert into role_permissions(role_id, permission_code) values
    ('00000000-0000-0000-0000-000000000001', 'AUTOMATION_READ'),
    ('00000000-0000-0000-0000-000000000001', 'AUTOMATION_MANAGE'),
    ('00000000-0000-0000-0000-000000000001', 'MODEL_MANAGE'),
    ('00000000-0000-0000-0000-000000000002', 'AUTOMATION_READ'),
    ('00000000-0000-0000-0000-000000000003', 'AUTOMATION_READ'),
    ('00000000-0000-0000-0000-000000000004', 'AUTOMATION_READ'),
    ('00000000-0000-0000-0000-000000000005', 'AUTOMATION_READ');

alter table workspace_automation_settings enable row level security;
alter table workspace_automation_settings force row level security;
create policy workspace_automation_settings_tenant_policy on workspace_automation_settings for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

alter table workspace_model_settings enable row level security;
alter table workspace_model_settings force row level security;
create policy workspace_model_settings_tenant_policy on workspace_model_settings for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

alter table wiki_evolution_runs enable row level security;
alter table wiki_evolution_runs force row level security;
create policy wiki_evolution_runs_tenant_policy on wiki_evolution_runs for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

grant select, insert, update, delete on workspace_model_settings, wiki_evolution_runs to llm_wiki_app;
