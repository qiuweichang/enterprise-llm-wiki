package com.llmwiki.query;

import com.llmwiki.background.OpenAiCompatibleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于已编译 Wiki 页面生成带编号引用的回答；模型不可用时返回可验证的抽取式答案。
 */
@Component
public class AnswerComposer {
    private static final Logger log = LoggerFactory.getLogger(AnswerComposer.class);
    private final OpenAiCompatibleClient modelClient;

    /**
     * 创建回答编排器。
     *
     * @param modelClient 统一的空间级模型客户端
     */
    public AnswerComposer(OpenAiCompatibleClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * 生成回答并保证引用编号与传入页面顺序一致。
     *
     * @param organizationId 当前组织 ID
     * @param workspaceId 当前空间 ID
     * @param question 用户问题
     * @param conversationContext 最近会话上下文，可为空
     * @param pages 检索页面
     * @return Markdown 回答及实际生成方式
     */
    public ComposedAnswer compose(UUID organizationId, UUID workspaceId, String question, String conversationContext,
                                  List<QueryRetrievalService.RetrievedPage> pages) {
        return compose(organizationId, workspaceId, question, conversationContext, pages, true);
    }

    /** 根据问 Wiki 的开关决定是否调用模型；关闭时只返回可核验的 Wiki 摘录。 */
    public ComposedAnswer compose(UUID organizationId, UUID workspaceId, String question, String conversationContext,
                                  List<QueryRetrievalService.RetrievedPage> pages, boolean useModel) {
        if (pages.isEmpty()) {
            return new ComposedAnswer("当前已发布 Wiki 中没有足够信息回答这个问题。可以先添加来源，或扩大问题范围。",
                    "NO_KNOWLEDGE", null, null);
        }
        if (!useModel) {
            return new ComposedAnswer(composeExtractively(pages), "EXTRACTIVE", null, null);
        }
        try {
            OpenAiCompatibleClient.Completion completion = composeWithModel(organizationId, workspaceId, question,
                    conversationContext, pages);
            if (completion != null) {
                return new ComposedAnswer(completion.content(), "AI", completion.provider(), completion.modelName());
            }
        } catch (com.llmwiki.background.ModelQuotaExceededException exception) {
            log.error("LLM query answer blocked by provider quota", exception);
            return new ComposedAnswer("模型额度已达到服务商限制，暂时无法启动大模型回答。请等待额度窗口重置后重试；以下为当前 Wiki 的可核验摘录：\n\n" + composeExtractively(pages),
                    "MODEL_QUOTA", null, null);
        } catch (Exception exception) {
            log.error("LLM query answer failed; extractive answer will be used", exception);
        }
        return new ComposedAnswer(composeExtractively(pages), "EXTRACTIVE", null, null);
    }

    /** 调用 OpenAI 兼容模型，明确禁止使用上下文外事实。 */
    private OpenAiCompatibleClient.Completion composeWithModel(UUID organizationId, UUID workspaceId, String question,
                                                               String conversationContext,
                                                               List<QueryRetrievalService.RetrievedPage> pages) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < pages.size(); index++) {
            QueryRetrievalService.RetrievedPage page = pages.get(index);
            context.append("[来源 ").append(index + 1).append("] ").append(page.title()).append("\n")
                    .append(limit(page.contentMarkdown(), 6000)).append("\n\n");
        }
        return modelClient.completeText(organizationId, workspaceId,
                "仅依据给定 Wiki 页面回答。每个结论用 [1] 形式引用；信息不足时明确说明，不得编造。",
                (conversationContext == null || conversationContext.isBlank() ? "" :
                        "最近会话（仅用于理解追问，不作为事实来源）：\n" + conversationContext + "\n\n") +
                        "当前问题：" + question + "\n\n可引用 Wiki 页面：\n" + context);
    }

    /** 将每个高相关页面的首个有意义段落组成可追溯降级答案。 */
    private String composeExtractively(List<QueryRetrievalService.RetrievedPage> pages) {
        StringBuilder answer = new StringBuilder("基于当前已发布 Wiki，可以归纳为：\n\n");
        AtomicInteger citation = new AtomicInteger(1);
        pages.stream().limit(5).forEach(page -> answer.append("- **").append(page.title()).append("**：")
                .append(firstMeaningfulParagraph(page.contentMarkdown())).append(" [")
                .append(citation.getAndIncrement()).append("]\n"));
        // 生成方式由 answerMode 单独展示；主动关闭聊天模型不等于“没有配置模型”。
        return answer.toString();
    }

    /** 提取首个非标题、非引用的正文段落。 */
    private String firstMeaningfulParagraph(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "该页面目前没有可展示的摘要。";
        }
        for (String block : markdown.split("\\R\\s*\\R")) {
            String clean = block.replaceAll("(?m)^#{1,6}\\s+.*$", "")
                    .replaceAll("(?m)^>\\s?", "").trim();
            if (!clean.isBlank()) {
                return limit(clean.replaceAll("\\s+", " "), 320);
            }
        }
        return "请打开页面查看完整内容。";
    }

    /** 限制上下文或摘要长度。 */
    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 回答正文及其实际生成方式，供持久化会话明确展示是否调用大模型。 */
    public record ComposedAnswer(String markdown, String answerMode, String modelProvider, String modelName) { }
}
