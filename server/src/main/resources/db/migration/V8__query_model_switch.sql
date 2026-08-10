-- 问 Wiki 每条问题记录是否允许调用模型；默认开启以兼容旧会话。
alter table query_messages add column if not exists use_model boolean not null default true;
