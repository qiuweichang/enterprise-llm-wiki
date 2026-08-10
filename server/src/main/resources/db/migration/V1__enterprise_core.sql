create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

create table organizations (
    id uuid primary key default gen_random_uuid(),
    slug text not null unique,
    name text not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table users (
    id uuid primary key default gen_random_uuid(),
    email text not null,
    password_hash text not null,
    display_name text not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'LOCKED', 'DISABLED')),
    token_version bigint not null default 0,
    last_login_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index users_email_lower_uq on users (lower(email));

create table organization_members (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    status text not null default 'ACTIVE' check (status in ('INVITED', 'ACTIVE', 'SUSPENDED')),
    created_at timestamptz not null default now(),
    unique (organization_id, user_id)
);
create index organization_members_user_idx on organization_members (user_id, status);

create table workspaces (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations(id) on delete cascade,
    slug text not null,
    name text not null,
    description text not null default '',
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'ARCHIVED')),
    version bigint not null default 0,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (organization_id, slug),
    unique (organization_id, id)
);
create index workspaces_organization_idx on workspaces (organization_id, status);

create table workspace_members (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null references users(id) on delete cascade,
    status text not null default 'ACTIVE' check (status in ('INVITED', 'ACTIVE', 'SUSPENDED')),
    joined_at timestamptz not null default now(),
    unique (workspace_id, user_id),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index workspace_members_user_idx on workspace_members (user_id, status, workspace_id);
create index workspace_members_tenant_idx on workspace_members (organization_id, workspace_id, status);

create table permissions (
    code text primary key,
    description text not null
);

create table roles (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid references organizations(id) on delete cascade,
    code text not null,
    name text not null,
    description text not null default '',
    system_role boolean not null default false,
    created_at timestamptz not null default now()
);
create unique index roles_system_code_uq on roles (code) where organization_id is null;
create unique index roles_organization_code_uq on roles (organization_id, code) where organization_id is not null;

create table role_permissions (
    role_id uuid not null references roles(id) on delete cascade,
    permission_code text not null references permissions(code) on delete cascade,
    primary key (role_id, permission_code)
);
create index role_permissions_permission_idx on role_permissions (permission_code, role_id);

create table workspace_member_roles (
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null,
    role_id uuid not null references roles(id) on delete cascade,
    assigned_by uuid references users(id),
    assigned_at timestamptz not null default now(),
    primary key (workspace_id, user_id, role_id),
    foreign key (workspace_id, user_id) references workspace_members(workspace_id, user_id) on delete cascade
);
create index workspace_member_roles_tenant_user_idx on workspace_member_roles (organization_id, workspace_id, user_id);

create table user_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    organization_id uuid not null references organizations(id) on delete cascade,
    workspace_id uuid not null references workspaces(id) on delete cascade,
    refresh_token_hash text not null unique,
    user_agent text,
    ip_address inet,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);
create index user_sessions_user_active_idx on user_sessions (user_id, expires_at) where revoked_at is null;

create table sources (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    source_type text not null check (source_type in ('TEXT', 'URL', 'FILE', 'AUDIO', 'VIDEO')),
    title text not null,
    canonical_uri text,
    status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'ARCHIVED')),
    content_hash text,
    latest_version_id uuid,
    created_by uuid not null references users(id),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index sources_tenant_status_idx on sources (organization_id, workspace_id, status, created_at desc);
create unique index sources_tenant_uri_uq on sources (organization_id, workspace_id, canonical_uri) where canonical_uri is not null and status <> 'ARCHIVED';

create table source_versions (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    source_id uuid not null references sources(id) on delete cascade,
    version_no integer not null,
    raw_object_key text,
    extracted_markdown text not null default '',
    content_hash text not null,
    extraction_metadata jsonb not null default '{}'::jsonb,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    unique (source_id, version_no),
    unique (source_id, content_hash),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index source_versions_tenant_source_idx on source_versions (organization_id, workspace_id, source_id, version_no desc);
alter table sources add constraint sources_latest_version_fk foreign key (latest_version_id) references source_versions(id);

create table wiki_pages (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    slug text not null,
    title text not null,
    page_type text not null default 'TOPIC' check (page_type in ('README', 'CONTEXT', 'ENTITY', 'TOPIC', 'SYNTHESIS', 'QUERY')),
    status text not null default 'PUBLISHED' check (status in ('PUBLISHED', 'ARCHIVED')),
    current_revision_id uuid,
    revision_no integer not null default 0,
    content_markdown text not null default '',
    content_hash text not null default '',
    version bigint not null default 0,
    created_by uuid not null references users(id),
    updated_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    search_vector tsvector generated always as (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(content_markdown, '')), 'B')
    ) stored,
    unique (organization_id, workspace_id, slug),
    unique (organization_id, workspace_id, id),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index wiki_pages_tenant_updated_idx on wiki_pages (organization_id, workspace_id, updated_at desc, id);
create index wiki_pages_search_idx on wiki_pages using gin (search_vector);
create index wiki_pages_title_trgm_idx on wiki_pages using gin (title gin_trgm_ops);

create table change_sets (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    change_type text not null check (change_type in ('MANUAL', 'INGESTION', 'MAINTENANCE', 'MCP')),
    status text not null default 'PENDING' check (status in ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'FAILED')),
    title text not null,
    summary text not null default '',
    risk text not null default 'LOW' check (risk in ('LOW', 'MEDIUM', 'HIGH')),
    confidence numeric(5,4) not null default 0.5 check (confidence >= 0 and confidence <= 1),
    source_id uuid references sources(id),
    proposed_by uuid not null references users(id),
    version bigint not null default 0,
    submitted_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index change_sets_pending_idx on change_sets (organization_id, workspace_id, created_at) where status = 'PENDING';

create table change_set_actions (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    change_set_id uuid not null references change_sets(id) on delete cascade,
    action_type text not null check (action_type in ('CREATE_PAGE', 'UPDATE_PAGE', 'ARCHIVE_PAGE')),
    page_id uuid references wiki_pages(id),
    base_revision_id uuid,
    proposed_slug text not null,
    proposed_title text not null,
    proposed_page_type text not null,
    proposed_content_markdown text not null,
    content_hash text not null,
    ordinal integer not null,
    created_at timestamptz not null default now(),
    unique (change_set_id, ordinal),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index change_set_actions_change_idx on change_set_actions (organization_id, workspace_id, change_set_id, ordinal);

create table wiki_page_revisions (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    page_id uuid not null references wiki_pages(id) on delete cascade,
    revision_no integer not null,
    title text not null,
    content_markdown text not null,
    content_hash text not null,
    change_summary text not null default '',
    change_set_id uuid references change_sets(id),
    published_by uuid not null references users(id),
    published_at timestamptz not null default now(),
    unique (page_id, revision_no),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index wiki_page_revisions_page_idx on wiki_page_revisions (organization_id, workspace_id, page_id, revision_no desc);
alter table wiki_pages add constraint wiki_pages_current_revision_fk foreign key (current_revision_id) references wiki_page_revisions(id);
alter table change_set_actions add constraint change_set_actions_base_revision_fk foreign key (base_revision_id) references wiki_page_revisions(id);

create table reviews (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    change_set_id uuid not null references change_sets(id) on delete cascade,
    reviewer_id uuid not null references users(id),
    decision text not null check (decision in ('APPROVE', 'REJECT')),
    comment text not null default '',
    created_at timestamptz not null default now(),
    unique (change_set_id, reviewer_id)
);
create index reviews_change_idx on reviews (organization_id, workspace_id, change_set_id, created_at);

create table evidence (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    page_revision_id uuid not null references wiki_page_revisions(id) on delete cascade,
    source_id uuid not null references sources(id),
    source_version_id uuid not null references source_versions(id),
    quote_text text not null default '',
    locator text not null default '',
    confidence numeric(5,4) not null default 0.5 check (confidence >= 0 and confidence <= 1),
    created_at timestamptz not null default now()
);
create index evidence_revision_idx on evidence (organization_id, workspace_id, page_revision_id);
create index evidence_source_idx on evidence (source_id, source_version_id);

create table wiki_relations (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    from_page_id uuid not null references wiki_pages(id) on delete cascade,
    to_page_id uuid not null references wiki_pages(id) on delete cascade,
    relation_type text not null check (relation_type in ('WIKILINK', 'RELATED', 'CONTRADICTS', 'SUPPORTS')),
    evidence_id uuid references evidence(id),
    created_at timestamptz not null default now(),
    unique (from_page_id, to_page_id, relation_type)
);
create index wiki_relations_from_idx on wiki_relations (organization_id, workspace_id, from_page_id);
create index wiki_relations_to_idx on wiki_relations (organization_id, workspace_id, to_page_id);

create table ingestion_jobs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    source_id uuid not null references sources(id) on delete cascade,
    requested_by uuid not null references users(id),
    status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    attempts integer not null default 0,
    max_attempts integer not null default 5,
    lease_owner text,
    lease_until timestamptz,
    next_attempt_at timestamptz not null default now(),
    payload jsonb not null default '{}'::jsonb,
    error_message text,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index ingestion_jobs_claim_idx on ingestion_jobs (next_attempt_at, created_at) where status = 'PENDING';
create index ingestion_jobs_tenant_idx on ingestion_jobs (organization_id, workspace_id, created_at desc);

create table query_runs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null references users(id),
    question text not null,
    normalized_question text not null,
    answer_markdown text not null,
    citations jsonb not null default '[]'::jsonb,
    cache_hit boolean not null default false,
    duration_ms bigint not null,
    created_at timestamptz not null default now()
);
create index query_runs_user_idx on query_runs (organization_id, workspace_id, user_id, created_at desc);

create table audit_logs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid,
    actor_id uuid references users(id),
    action text not null,
    resource_type text not null,
    resource_id uuid,
    request_id text,
    before_state jsonb,
    after_state jsonb,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index audit_logs_tenant_time_idx on audit_logs (organization_id, workspace_id, created_at desc, id);

create table outbox_events (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid,
    aggregate_type text not null,
    aggregate_id uuid not null,
    event_type text not null,
    payload jsonb not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    published_at timestamptz,
    error_message text,
    created_at timestamptz not null default now()
);
create index outbox_events_pending_idx on outbox_events (available_at, created_at) where status = 'PENDING';

insert into permissions(code, description) values
('PAGE_READ', 'Read published wiki pages'),
('PAGE_CREATE', 'Propose a new wiki page'),
('PAGE_CREATE_PUBLISH', 'Publish a new wiki page without review'),
('PAGE_UPDATE_PROPOSE', 'Propose changes to an existing page'),
('REVIEW_READ', 'Read pending change sets'),
('REVIEW_APPROVE', 'Approve or reject change sets'),
('SOURCE_READ', 'Read source metadata and evidence'),
('SOURCE_CREATE', 'Create and ingest sources'),
('QUERY_EXECUTE', 'Ask questions against the compiled wiki'),
('EXPORT_WIKI', 'Export the workspace as Obsidian-compatible Markdown'),
('MEMBER_MANAGE', 'Manage workspace members'),
('ROLE_MANAGE', 'Manage role assignments'),
('AUDIT_READ', 'Read immutable audit events'),
('MCP_USE', 'Use the MCP endpoint');

insert into roles(id, code, name, description, system_role) values
('00000000-0000-0000-0000-000000000001', 'ORG_ADMIN', 'Organization administrator', 'Full workspace administration and review authority', true),
('00000000-0000-0000-0000-000000000002', 'EDITOR', 'Editor', 'Creates sources, publishes new pages and proposes updates', true),
('00000000-0000-0000-0000-000000000003', 'CONTRIBUTOR', 'Contributor', 'Contributes sources and proposes pages and updates', true),
('00000000-0000-0000-0000-000000000004', 'REVIEWER', 'Reviewer', 'Reads knowledge and approves proposed changes', true),
('00000000-0000-0000-0000-000000000005', 'VIEWER', 'Viewer', 'Reads and queries published knowledge', true);

insert into role_permissions(role_id, permission_code)
select '00000000-0000-0000-0000-000000000001'::uuid, code from permissions;
insert into role_permissions(role_id, permission_code) values
('00000000-0000-0000-0000-000000000002', 'PAGE_READ'),
('00000000-0000-0000-0000-000000000002', 'PAGE_CREATE'),
('00000000-0000-0000-0000-000000000002', 'PAGE_CREATE_PUBLISH'),
('00000000-0000-0000-0000-000000000002', 'PAGE_UPDATE_PROPOSE'),
('00000000-0000-0000-0000-000000000002', 'SOURCE_READ'),
('00000000-0000-0000-0000-000000000002', 'SOURCE_CREATE'),
('00000000-0000-0000-0000-000000000002', 'QUERY_EXECUTE'),
('00000000-0000-0000-0000-000000000002', 'EXPORT_WIKI'),
('00000000-0000-0000-0000-000000000002', 'MCP_USE'),
('00000000-0000-0000-0000-000000000003', 'PAGE_READ'),
('00000000-0000-0000-0000-000000000003', 'PAGE_CREATE'),
('00000000-0000-0000-0000-000000000003', 'PAGE_UPDATE_PROPOSE'),
('00000000-0000-0000-0000-000000000003', 'SOURCE_READ'),
('00000000-0000-0000-0000-000000000003', 'SOURCE_CREATE'),
('00000000-0000-0000-0000-000000000003', 'QUERY_EXECUTE'),
('00000000-0000-0000-0000-000000000003', 'MCP_USE'),
('00000000-0000-0000-0000-000000000004', 'PAGE_READ'),
('00000000-0000-0000-0000-000000000004', 'REVIEW_READ'),
('00000000-0000-0000-0000-000000000004', 'REVIEW_APPROVE'),
('00000000-0000-0000-0000-000000000004', 'SOURCE_READ'),
('00000000-0000-0000-0000-000000000004', 'QUERY_EXECUTE'),
('00000000-0000-0000-0000-000000000004', 'EXPORT_WIKI'),
('00000000-0000-0000-0000-000000000004', 'MCP_USE'),
('00000000-0000-0000-0000-000000000005', 'PAGE_READ'),
('00000000-0000-0000-0000-000000000005', 'SOURCE_READ'),
('00000000-0000-0000-0000-000000000005', 'QUERY_EXECUTE');

create or replace function llm_wiki_current_organization_id() returns uuid
language sql stable as $$
    select nullif(current_setting('app.current_organization_id', true), '')::uuid
$$;

create or replace function llm_wiki_current_workspace_id() returns uuid
language sql stable as $$
    select nullif(current_setting('app.current_workspace_id', true), '')::uuid
$$;

do $$
declare table_name text;
begin
    foreach table_name in array array[
        'sources', 'source_versions', 'wiki_pages', 'wiki_page_revisions',
        'change_sets', 'change_set_actions', 'reviews', 'evidence',
        'wiki_relations', 'query_runs'
    ] loop
        execute format('alter table %I enable row level security', table_name);
        execute format('alter table %I force row level security', table_name);
        execute format(
            'create policy %I_tenant_policy on %I for all using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id()) with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())',
            table_name, table_name
        );
    end loop;
end $$;
