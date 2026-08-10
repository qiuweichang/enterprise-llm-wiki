create table workspace_automation_settings (
    organization_id uuid not null,
    workspace_id uuid primary key,
    automatic_url_refresh boolean not null default true,
    refresh_interval_hours integer not null default 24 check (refresh_interval_hours between 1 and 8760),
    maintenance_enabled boolean not null default true,
    updated_by uuid references users(id),
    updated_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);

insert into workspace_automation_settings(organization_id, workspace_id)
select organization_id, id from workspaces
on conflict (workspace_id) do nothing;

grant select, insert, update, delete on workspace_automation_settings to llm_wiki_app;

