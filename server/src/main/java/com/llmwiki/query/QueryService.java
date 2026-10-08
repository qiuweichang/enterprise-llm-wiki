package com.llmwiki.query;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.ContentHash;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;

/**
 * 编排 Query 流程：规范化、空间版本缓存、混合检索、图扩展、回答生成、引用和运行审计。
 */
@Service
public class QueryService {
    private final QueryRetrievalService retrievalService;
    private final AnswerComposer answerComposer;
    private final WikiCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final SemanticIndexService semanticIndex;
    private final EmbeddingClient embeddingClient;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryService.class);

    /** 创建查询服务。 */
    public QueryService(QueryRetrievalService retrievalService, AnswerComposer answerComposer,
                        WikiCacheService cacheService, ObjectMapper objectMapper,
                        SemanticIndexService semanticIndex, EmbeddingClient embeddingClient) {
        this.retrievalService = retrievalService;
        this.answerComposer = answerComposer;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.semanticIndex = semanticIndex;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 执行一次可追溯知识查询。
     *
     * @param question 原始问题
     * @return 回答、重写问题、引用和缓存状态
     */
    public QueryResponse query(String question) {
        return queryForUser(RequestContext.require(), question, question, "", true);
    }

    /** 执行一次同步查询，并显式传入是否允许进入大模型回答阶段。 */
    public QueryResponse query(String question, boolean useModel) {
        return queryForUser(RequestContext.require(), question, question, "", useModel);
    }

    /**
     * 为持久化会话后台任务执行查询，允许使用最近对话辅助理解追问。
     *
     * @param user 会话所有者
     * @param question 当前问题
     * @param retrievalText 用于检索的当前问题及必要上文
     * @param conversationContext 最近对话上下文
     * @return 可持久化回答
     */
    public QueryResponse queryForUser(AuthenticatedUser user, String question, String retrievalText,
                                      String conversationContext) {
        return queryForUser(user, question, retrievalText, conversationContext, true);
    }

    /** 执行一次查询并按调用方开关决定是否进入大模型回答阶段。 */
    public QueryResponse queryForUser(AuthenticatedUser user, String question, String retrievalText,
                                      String conversationContext, boolean useModel) {
        if (question == null || question.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUESTION_REQUIRED", "请输入问题");
        }
        String normalized = normalize(question);
        String normalizedRetrieval = normalize(retrievalText == null ? question : retrievalText);
        long started = System.nanoTime();
        long epoch = cacheService.workspaceEpoch(user.organizationId(), user.workspaceId());
        var index = semanticIndex.snapshot(user);
        String cacheKey = "llm-wiki:query:semantic-v1:" + user.organizationId() + ":" + user.workspaceId() + ":" + epoch + ":" +
                index.fingerprint() + ":" + index.enabled() + ":" + index.minScore() + ":" +
                ContentHash.sha256(normalizedRetrieval + "\n" + (conversationContext == null ? "" : conversationContext) + "\nmodel=" + useModel);
        JavaType type = objectMapper.getTypeFactory().constructType(QueryResponse.class);
        java.util.Optional<QueryResponse> cached = cacheService.get(cacheKey, type);
        if (cached.isPresent()) {
            QueryResponse value = new QueryResponse(cached.get().question(), cached.get().rewrittenQuery(),
                    cached.get().answerMarkdown(), cached.get().citations(), true, cached.get().answerMode(),
                    cached.get().modelProvider(), cached.get().modelName(),
                    (System.nanoTime() - started) / 1_000_000,cached.get().retrievalMode(),cached.get().retrievalMessage());
            retrievalService.record(user, question.trim(), normalizedRetrieval, value.answerMarkdown(), value.citations(), true,
                    value.durationMs(), value.answerMode(), value.modelProvider(), value.modelName(),value.retrievalMode(),value.retrievalMessage());
            return value;
        }
        List<Double> vector = List.of();
        String retrievalMode = "KEYWORD", retrievalMessage = "语义检索已关闭";
        if (index.enabled()) {
            if (index.ready() == 0) {
                retrievalMode = "INDEXING";
                retrievalMessage = index.failed()>0 ? "语义索引构建失败，请到模型与 MCP 查看原因" : "语义索引尚未就绪，暂用关键词检索";
            } else try {
                // 问题向量短超时；只取末尾以保留当前问题，避免多轮历史把当前意图截掉。
                String text = normalizedRetrieval.substring(Math.max(0,normalizedRetrieval.length()-320));
                vector = embeddingClient.embed(List.of(text),true).getFirst();
                retrievalMode = "HYBRID";
                retrievalMessage = index.ready()<index.total() ? "关键词 + 语义检索；部分页面索引仍在构建" : "关键词 + 语义检索 + 关联扩展";
            } catch (Exception error) {
                retrievalMode = "DEGRADED"; retrievalMessage = error.getMessage();
                log.error("语义查询降级 workspaceId={} reason={}",user.workspaceId(),retrievalMessage);
            }
        }
        List<QueryRetrievalService.RetrievedPage> pages = retrievalService.retrieve(user, normalizedRetrieval, vector,index.minScore());
        log.info("混合检索完成 workspaceId={} mode={} pages={}",user.workspaceId(),retrievalMode,pages.size());
        List<Citation> citations = java.util.stream.IntStream.range(0, pages.size())
                .mapToObj(position -> {
                    var page = pages.get(position);
                    return new Citation(position + 1, page.id(), page.slug(), page.title(), page.revisionNo(),
                            excerpt(page.contentMarkdown()));
                }).toList();
        AnswerComposer.ComposedAnswer composed = answerComposer.compose(user.organizationId(), user.workspaceId(), question.trim(),
                conversationContext, pages, useModel);
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        QueryResponse response = new QueryResponse(question.trim(), normalized, composed.markdown(), citations, false,
                composed.answerMode(), composed.modelProvider(), composed.modelName(), durationMs,retrievalMode,retrievalMessage);
        // 服务失败或索引未就绪时不能缓存降级回答，恢复后下次提问应立即重新尝试语义召回。
        if ("HYBRID".equals(retrievalMode) || !index.enabled()) cacheService.put(cacheKey, response, Duration.ofMinutes(15));
        retrievalService.record(user, question.trim(), normalizedRetrieval, composed.markdown(), citations, false,
                durationMs, composed.answerMode(), composed.modelProvider(), composed.modelName(),retrievalMode,retrievalMessage);
        return response;
    }

    /** Unicode 规范化并压缩空白，修复旧版 rewritten_query 丢失和特殊字符问题。 */
    private String normalize(String question) {
        return Normalizer.normalize(question, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    /** 提取引用预览。 */
    private String excerpt(String markdown) {
        String plain = markdown == null ? "" : markdown.replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("[`*_>#]", "").replaceAll("\\s+", " ").trim();
        return plain.length() <= 220 ? plain : plain.substring(0, 220) + "…";
    }

    /** 查询引用。 */
    public record Citation(int number, java.util.UUID pageId, String slug, String title, int revisionNo, String excerpt) { }
    /** 查询响应。 */
    public record QueryResponse(String question, String rewrittenQuery, String answerMarkdown,
                                List<Citation> citations, boolean cacheHit, String answerMode,
                                String modelProvider, String modelName, long durationMs,
                                String retrievalMode, String retrievalMessage) { }
}
