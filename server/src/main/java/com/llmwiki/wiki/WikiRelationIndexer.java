package com.llmwiki.wiki;

import com.llmwiki.common.Slugifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将页面正文中的 WikiLink 重建为关系表索引。
 * Markdown 是关系事实来源，关系表仅服务图谱查询；集中实现可保证自动发布与审核发布使用相同语义。
 */
@Component
public class WikiRelationIndexer {
    /** 支持标题、标题锚点和别名三种 Obsidian 常见 WikiLink 写法。 */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^]#|]+)(?:#[^]|]+)?(?:\\|[^]]+)?]]");
    private final JdbcClient jdbc;

    /**
     * 创建关系索引器。
     *
     * @param jdbc 继承当前事务与租户会话的数据库客户端
     */
    public WikiRelationIndexer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子替换某页面发出的 WikiLink 关系。
     * 调用方应先确保同一变更集内的全部目标页面已创建，否则尚不存在的目标不会被错误写入。
     *
     * @param fromPageId 发出链接的页面 ID
     * @param content 已发布 Markdown 正文
     * @return 成功建立的关系数量
     */
    public int rebuildOutgoing(UUID fromPageId, String content) {
        jdbc.sql("delete from wiki_relations where from_page_id = :pageId and relation_type = 'WIKILINK'")
                .param("pageId", fromPageId).update();
        Matcher matcher = WIKILINK.matcher(content == null ? "" : content);
        int indexed = 0;
        while (matcher.find()) {
            String slug = Slugifier.slugify(matcher.group(1).trim());
            indexed += jdbc.sql("""
                            insert into wiki_relations(organization_id, workspace_id, from_page_id, to_page_id, relation_type)
                            select p.organization_id, p.workspace_id, :fromPageId, p.id, 'WIKILINK'
                            from wiki_pages p
                            where p.slug = :slug and p.status = 'PUBLISHED' and p.id <> :fromPageId
                            on conflict (from_page_id, to_page_id, relation_type) do nothing
                            """).param("fromPageId", fromPageId).param("slug", slug).update();
        }
        return indexed;
    }
}
