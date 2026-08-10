alter table query_messages
    add column answer_mode text check (answer_mode in ('AI', 'EXTRACTIVE', 'NO_KNOWLEDGE')),
    add column model_provider text,
    add column model_name text;

alter table query_runs
    add column answer_mode text check (answer_mode in ('AI', 'EXTRACTIVE', 'NO_KNOWLEDGE')),
    add column model_provider text,
    add column model_name text;

