create table api_keys (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    organization_id uuid not null references organizations(id) on delete cascade,
    workspace_id uuid not null references workspaces(id) on delete cascade,
    name text not null,
    token_hash text not null unique,
    token_prefix text not null,
    expires_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);
create index api_keys_active_user_idx on api_keys (user_id, workspace_id, created_at desc) where revoked_at is null;

