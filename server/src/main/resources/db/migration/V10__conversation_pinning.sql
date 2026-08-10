-- 会话置顶由数据库保存，保证用户离开页面后仍保持顺序。
alter table query_conversations add column if not exists pinned boolean not null default false;
create index if not exists query_conversations_pinned_idx
    on query_conversations (organization_id, workspace_id, user_id, pinned desc, last_message_at desc);
