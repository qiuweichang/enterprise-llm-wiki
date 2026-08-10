do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'llm_wiki_app') then
        create role llm_wiki_app nologin nosuperuser nocreatedb nocreaterole noinherit;
    end if;
end $$;

grant usage on schema public to llm_wiki_app;
grant select, insert, update, delete on all tables in schema public to llm_wiki_app;
grant usage, select on all sequences in schema public to llm_wiki_app;
grant execute on function llm_wiki_current_organization_id() to llm_wiki_app;
grant execute on function llm_wiki_current_workspace_id() to llm_wiki_app;
alter default privileges in schema public grant select, insert, update, delete on tables to llm_wiki_app;
alter default privileges in schema public grant usage, select on sequences to llm_wiki_app;

