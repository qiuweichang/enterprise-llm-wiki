create table workspace_model_connection_tests (
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null references users(id),
    configuration_fingerprint text not null,
    provider text not null,
    model_name text not null,
    latency_ms bigint not null,
    response_preview text not null default '',
    tested_at timestamptz not null default now(),
    expires_at timestamptz not null,
    primary key (workspace_id, user_id),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);

create table query_conversations (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null references users(id),
    title text not null,
    last_message_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (organization_id, workspace_id, id),
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index query_conversations_user_time_idx
    on query_conversations (organization_id, workspace_id, user_id, last_message_at desc, id);

create table query_messages (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null,
    workspace_id uuid not null,
    conversation_id uuid not null,
    user_id uuid not null references users(id),
    sequence_no integer not null,
    role text not null check (role in ('USER', 'ASSISTANT')),
    status text not null check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    content_markdown text not null default '',
    citations jsonb not null default '[]'::jsonb,
    cache_hit boolean not null default false,
    duration_ms bigint,
    error_message text,
    lease_owner text,
    lease_until timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    unique (conversation_id, sequence_no),
    foreign key (organization_id, workspace_id, conversation_id)
        references query_conversations(organization_id, workspace_id, id) on delete cascade,
    foreign key (organization_id, workspace_id) references workspaces(organization_id, id) on delete cascade
);
create index query_messages_conversation_idx
    on query_messages (organization_id, workspace_id, conversation_id, sequence_no);
create index query_messages_claim_idx on query_messages (created_at)
    where role = 'ASSISTANT' and status in ('PENDING', 'RUNNING');
create unique index query_messages_conversation_active_uq on query_messages (conversation_id)
    where role = 'ASSISTANT' and status in ('PENDING', 'RUNNING');

alter table workspace_model_connection_tests enable row level security;
alter table workspace_model_connection_tests force row level security;
create policy workspace_model_connection_tests_tenant_policy on workspace_model_connection_tests for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

alter table query_conversations enable row level security;
alter table query_conversations force row level security;
create policy query_conversations_tenant_policy on query_conversations for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

alter table query_messages enable row level security;
alter table query_messages force row level security;
create policy query_messages_tenant_policy on query_messages for all
    using (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id())
    with check (organization_id = llm_wiki_current_organization_id() and workspace_id = llm_wiki_current_workspace_id());

grant select, insert, update, delete on workspace_model_connection_tests,
    query_conversations, query_messages to llm_wiki_app;
