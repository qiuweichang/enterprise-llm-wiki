-- 置信度不再作为企业 Wiki 的业务字段；审核只保留风险、状态和人工决策。
alter table change_sets drop column if exists confidence;
alter table evidence drop column if exists confidence;

-- 清理旧版本自动编译时写入的来源说明，页面正文只保留知识内容本身。
update wiki_page_revisions
set content_markdown = regexp_replace(content_markdown, E'> 本实体由《[^\\n]+》中的结构化章节编译。\\s*', '', 'g')
where content_markdown like '%本实体由%结构化章节编译%';
update wiki_pages
set content_markdown = regexp_replace(content_markdown, E'> 本实体由《[^\\n]+》中的结构化章节编译。\\s*', '', 'g')
where content_markdown like '%本实体由%结构化章节编译%';
