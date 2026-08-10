package com.llmwiki.background;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.common.Slugifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将清洗后的来源编译成一组相互链接、可独立维护的 Wiki 页面。
 * 编译结果以中心主题页为入口，以实体页承载稳定概念；数据库关系仅是这些 WikiLink 的派生索引。
 */
@Component
public class KnowledgeCompiler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeCompiler.class);
    /** Markdown 标题解析表达式，用于无模型时从结构化文档建立知识边界。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,3})\\s+(.+?)\\s*$");
    /** 过于宽泛的章节名不适合作为独立实体，但仍保留在中心主题页中。 */
    private static final Set<String> GENERIC_HEADINGS = Set.of(
            "产品背景", "产品简介", "产品架构", "产品模块介绍", "产品核心能力", "功能清单", "应用赋能",
            "目标客户", "应用场景", "经典案例", "优势及价值", "公司简介", "概述", "简介", "背景", "总结");
    /** 单个来源最多拆出的实体数量，避免长目录一次产生无法审核的页面风暴。 */
    private static final int MAX_ENTITY_PAGES = 12;

    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleClient modelClient;

    /**
     * 创建知识编译器。
     *
     * @param objectMapper 模型 JSON 解析器
     * @param modelClient 租户级 OpenAI 兼容模型客户端
     */
    public KnowledgeCompiler(ObjectMapper objectMapper, OpenAiCompatibleClient modelClient) {
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
    }

    /**
     * 根据来源和可选中心页面生成多页候选知识。
     * 模型不可用或返回结构无效时自动使用确定性章节拆分，摄取任务不会因模型故障丢失。
     *
     * @param organizationId 来源所属组织 ID
     * @param workspaceId 来源所属空间 ID
     * @param sourceTitle 来源标题
     * @param sourceMarkdown 清洗后的来源 Markdown
     * @param currentPage 当前同名中心页面，可为空
     * @return 可作为一个原子变更集提交的编译结果
     */
    public CompilationResult compile(UUID organizationId, UUID workspaceId, String sourceTitle,
                                     String sourceMarkdown, CurrentPage currentPage) {
        try {
            CompilationResult compiled = compileWithModel(
                    organizationId, workspaceId, sourceTitle, sourceMarkdown, currentPage);
            if (compiled != null && !compiled.pages().isEmpty()) {
                return compiled;
            }
        } catch (ModelQuotaExceededException exception) {
            log.error("LLM multi-page compilation blocked by provider quota; deterministic compiler will be used sourceTitle={} message={}",
                    sourceTitle, exception.getMessage());
        } catch (Exception exception) {
            log.error("LLM multi-page compilation failed; deterministic compiler will be used sourceTitle={}",
                    sourceTitle, exception);
        }
        return compileDeterministically(sourceTitle, sourceMarkdown, currentPage);
    }

    /**
     * 调用 OpenAI 兼容接口生成严格的多页面 JSON，并过滤重复或空页面。
     * 每个页面必须通过 WikiLink 连接到中心主题，确保发布后图谱可从正文重建。
     */
    private CompilationResult compileWithModel(UUID organizationId, UUID workspaceId, String sourceTitle,
                                                String sourceMarkdown, CurrentPage currentPage) throws Exception {
        String rootTitle = currentPage == null ? cleanSourceTitle(sourceTitle) : currentPage.title();
        String current = currentPage == null ? "没有现有中心页面。" :
                "现有中心页标题：" + currentPage.title() + "\n现有正文：\n" + limit(currentPage.content(), 30000);
        String prompt = """
                你是企业知识 Wiki 编译器。请把来源编译成一个中心主题页和若干可独立复用的实体页，不要写聊天式回答。
                返回 JSON 对象，顶层字段必须是 summary、risk、pages。
                pages 是数组，每项必须有 title、pageType、markdown；pageType 只能是 ENTITY、TOPIC、SYNTHESIS。
                第一项必须是中心主题页，标题固定为“%s”。实体页选择长期稳定、可复用的产品、能力、模块、组织或概念，不要把“简介、背景、总结”等目录标题当实体。
                中心页必须用 [[实体标题]] 列出实体；每个实体页必须用 [[%s]] 链回中心页，并可链接其他直接相关实体。
                保留来源支持的事实；与现有内容冲突时明确写出分歧，不要静默覆盖。每页包含概要、关键事实和来源说明。
                risk 为 LOW、MEDIUM、HIGH。最多生成 12 个实体页。

                来源标题：%s
                %s
                新来源正文：
                %s
                """.formatted(rootTitle, rootTitle, sourceTitle, current, limit(sourceMarkdown, 50000));
        OpenAiCompatibleClient.Completion completion = modelClient.completeJson(organizationId, workspaceId,
                "你负责维护可审核、证据驱动、实体关系清晰的企业 Wiki。只输出 JSON。", prompt);
        if (completion == null) {
            return null;
        }
        String json = completion.content().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        JsonNode result = objectMapper.readTree(json);
        JsonNode pagesNode = result.path("pages");
        if (!pagesNode.isArray()) {
            return null;
        }
        List<CompiledPage> pages = new ArrayList<>();
        Set<String> slugs = new HashSet<>();
        for (JsonNode item : pagesNode) {
            String title = required(item.path("title").asText(), "");
            String markdown = required(item.path("markdown").asText(), "");
            if (title.isBlank() || markdown.isBlank()) {
                continue;
            }
            String slug = Slugifier.slugify(title);
            if (slugs.add(slug)) {
                pages.add(new CompiledPage(slug, title, normalizePageType(item.path("pageType").asText()), markdown));
            }
        }
        if (pages.isEmpty()) {
            return null;
        }
        String risk = normalizeRisk(result.path("risk").asText("MEDIUM"));
        return new CompilationResult(pages, result.path("summary").asText("AI 将来源编译为多页关联知识"),
                risk, true);
    }

    /**
     * 在没有可用模型时按 Markdown 章节生成中心主题和实体页面。
     * 二级、三级标题被视为候选知识边界，中心页保存完整来源摘要，实体页保留对应章节原文作为证据语境。
     */
    private CompilationResult compileDeterministically(String sourceTitle, String sourceMarkdown,
                                                        CurrentPage currentPage) {
        String rootTitle = currentPage == null ? cleanSourceTitle(sourceTitle) : currentPage.title();
        List<MarkdownSection> selected = selectKnowledgeSections(extractSections(sourceMarkdown));
        List<CompiledPage> pages = new ArrayList<>();
        List<String> entityTitles = selected.stream().map(MarkdownSection::title).toList();
        String links = entityTitles.isEmpty() ? "暂无可独立拆分的实体。" : entityTitles.stream()
                .map(title -> "- [[" + title + "]]" ).reduce((left, right) -> left + "\n" + right).orElse("");
        String rootBody = "# " + rootTitle + "\n\n> 此页面由来源自动编译，发布前后的证据均可追溯。\n\n" +
                "## 知识概览\n\n" + limit(sourceMarkdown.trim(), currentPage == null ? 24000 : 16000) +
                "\n\n## 关联实体\n\n" + links + "\n";
        if (currentPage != null) {
            rootBody = currentPage.content().trim() + "\n\n## 新来源补充\n\n" +
                    limit(sourceMarkdown.trim(), 16000) + "\n\n## 关联实体\n\n" + links + "\n";
        }
        pages.add(new CompiledPage(Slugifier.slugify(rootTitle), rootTitle,
                currentPage == null ? "TOPIC" : currentPage.pageType(), rootBody));
        for (int index = 0; index < selected.size(); index++) {
            MarkdownSection section = selected.get(index);
            String related = index > 0 ? "\n- [[" + selected.get(index - 1).title() + "]]" : "";
            String content = "# " + section.title() + "\n\n## 概要与事实\n\n" + limit(section.body().trim(), 7000) +
                    "\n\n## 关联知识\n\n- [[" + rootTitle + "]]" + related + "\n";
            pages.add(new CompiledPage(Slugifier.slugify(section.title()), section.title(), "ENTITY", content));
        }
        String risk = currentPage == null ? "LOW" : "MEDIUM";
        String summary = "从来源编译中心主题及 " + selected.size() + " 个关联实体";
        log.info("Deterministic multi-page compiler used sourceTitle={} pages={} updatingExisting={}",
                sourceTitle, pages.size(), currentPage != null);
        return new CompilationResult(pages, summary, risk, false);
    }

    /** 从 Markdown 中提取带层级和正文范围的章节。 */
    private List<MarkdownSection> extractSections(String markdown) {
        String value = markdown == null ? "" : markdown;
        Matcher matcher = HEADING.matcher(value);
        List<HeadingPosition> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new HeadingPosition(matcher.group(1).length(), normalizeHeadingTitle(matcher.group(2)),
                    matcher.start(), matcher.end()));
        }
        List<MarkdownSection> sections = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            HeadingPosition heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : value.length();
            sections.add(new MarkdownSection(heading.level(), heading.title(), value.substring(heading.end(), end).trim()));
        }
        return sections;
    }

    /** 选择内容充足、名称明确的二三级章节作为实体，并去除重复标题。 */
    private List<MarkdownSection> selectKnowledgeSections(List<MarkdownSection> sections) {
        List<MarkdownSection> selected = new ArrayList<>();
        Set<String> slugs = new HashSet<>();
        for (MarkdownSection section : sections) {
            if (selected.size() >= MAX_ENTITY_PAGES) {
                break;
            }
            String title = section.title();
            if (section.level() < 2 || title.length() < 2 || title.length() > 36 || GENERIC_HEADINGS.contains(title)
                    || section.body().length() < 40) {
                continue;
            }
            String slug = Slugifier.slugify(title);
            if (slugs.add(slug)) {
                selected.add(section);
            }
        }
        return selected;
    }

    /** 去掉序号、尾部标点和 Markdown 强调符，形成稳定实体标题。 */
    private String normalizeHeadingTitle(String value) {
        return value.replaceAll("^[\\d一二三四五六七八九十]+[.、．)）\\s]+", "")
                .replaceAll("[*_`]+", "").replaceAll("[：:。\\s]+$", "").trim();
    }

    /** 文件名只用于来源追踪，页面展示标题去掉扩展名和形如“【02】”的文档序号。 */
    private String cleanSourceTitle(String value) {
        String title = required(value, "未命名知识").replaceFirst("(?i)\\.(docx?|pdf|txt|md)$", "");
        return title.replaceFirst("^【[^】]+】\\s*", "").trim();
    }

    /** 限制输入长度，避免模型或数据库载荷失控。 */
    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n\n[内容已截断]";
    }

    /** 对空模型字段使用明确降级值。 */
    private String required(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 规范模型返回页面类型，防止未经校验的数据触发数据库约束异常。 */
    private String normalizePageType(String value) {
        return switch (value == null ? "TOPIC" : value.toUpperCase(Locale.ROOT)) {
            case "ENTITY" -> "ENTITY";
            case "SYNTHESIS" -> "SYNTHESIS";
            default -> "TOPIC";
        };
    }

    /** 规范模型返回风险等级。 */
    private String normalizeRisk(String value) {
        return switch (value == null ? "MEDIUM" : value.toUpperCase(Locale.ROOT)) {
            case "LOW" -> "LOW";
            case "HIGH" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    /** 当前中心页面上下文。 */
    public record CurrentPage(UUID id, UUID currentRevisionId, String slug, String title,
                              String pageType, String content) { }
    /** 单个候选页面；风险和来源生成方式由所属编译结果统一管理。 */
    public record CompiledPage(String slug, String title, String pageType, String markdown) { }
    /** 同一来源产生的原子多页编译结果。 */
    public record CompilationResult(List<CompiledPage> pages, String summary,
                                    String risk, boolean modelGenerated) { }
    /** 从来源提取的章节及其原文范围。 */
    private record MarkdownSection(int level, String title, String body) { }
    /** 标题在原始 Markdown 中的位置，用于准确切分章节正文。 */
    private record HeadingPosition(int level, String title, int start, int end) { }
}
