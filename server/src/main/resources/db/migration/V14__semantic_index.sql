-- 向量是可重建的派生数据。无需 pgvector 扩展，单位向量用 real[] 保存并精确计算余弦。
alter table query_messages add column retrieval_mode text not null default 'KEYWORD',
    add column retrieval_message text not null default '';
alter table query_runs add column retrieval_mode text not null default 'KEYWORD',
    add column retrieval_message text not null default '';
create table semantic_settings (
    organization_id uuid not null,
    workspace_id uuid primary key,
    enabled boolean not null default true,
    min_score double precision not null default 0.50 check (min_score between 0 and 1),
    updated_at timestamptz not null default clock_timestamp(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id,id) on delete cascade
);
create table semantic_jobs (
    page_id uuid primary key references wiki_pages(id) on delete cascade,
    organization_id uuid not null,
    workspace_id uuid not null,
    generation bigint not null default 1,
    status text not null default 'PENDING' check (status in ('PENDING','RUNNING','DONE','FAILED','ARCHIVED')),
    attempts integer not null default 0,
    lease_token uuid,
    lease_until timestamptz,
    next_run_at timestamptz not null default now(),
    last_error text not null default '',
    updated_at timestamptz not null default clock_timestamp(),
    foreign key (organization_id, workspace_id) references workspaces(organization_id,id) on delete cascade
);
create index semantic_jobs_claim_idx on semantic_jobs(organization_id,workspace_id,next_run_at) where status in ('PENDING','RUNNING');
create table semantic_chunks (
    organization_id uuid not null,
    workspace_id uuid not null,
    page_id uuid not null references wiki_pages(id) on delete cascade,
    revision_id uuid not null references wiki_page_revisions(id) on delete cascade,
    generation bigint not null,
    model_id text not null,
    ordinal integer not null,
    chunk_text text not null,
    embedding real[] not null check (cardinality(embedding)=512 and array_position(embedding,null) is null),
    primary key(page_id,ordinal),
    foreign key (organization_id, workspace_id) references workspaces(organization_id,id) on delete cascade
);
create index semantic_chunks_tenant_idx on semantic_chunks(organization_id,workspace_id,model_id);
do $$ declare t text; begin
    foreach t in array array['semantic_settings','semantic_jobs','semantic_chunks'] loop
        execute format('alter table %I enable row level security',t);
        execute format('alter table %I force row level security',t);
        execute format('create policy tenant_policy on %I for all using (organization_id=llm_wiki_current_organization_id() and workspace_id=llm_wiki_current_workspace_id()) with check (organization_id=llm_wiki_current_organization_id() and workspace_id=llm_wiki_current_workspace_id())',t);
        execute format('grant select,insert,update,delete on %I to llm_wiki_app',t);
    end loop;
end $$;

-- 发布事务内标记失效；审核提案不更新 wiki_pages，因此不会提前建立待审核内容的索引。
create function llm_wiki_enqueue_semantic() returns trigger language plpgsql as $$
begin
    if new.current_revision_id is null then return new; end if;
    insert into semantic_jobs(page_id,organization_id,workspace_id,status)
    values(new.id,new.organization_id,new.workspace_id,case when new.status='PUBLISHED' then 'PENDING' else 'ARCHIVED' end)
    on conflict(page_id) do update set generation=semantic_jobs.generation+1,
        status=excluded.status, attempts=0, lease_token=null, lease_until=null,
        last_error='',next_run_at=now(),updated_at=clock_timestamp();
    return new;
end $$;
create trigger wiki_pages_semantic_changed after insert or update of current_revision_id,title,content_markdown,status
    on wiki_pages for each row execute function llm_wiki_enqueue_semantic();
-- 迁移时一次性排队已有发布页面，不改变任何正文、审核或关系。
insert into semantic_jobs(page_id,organization_id,workspace_id)
select id,organization_id,workspace_id from wiki_pages where status='PUBLISHED' and current_revision_id is not null;
