package com.llmwiki.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 从主流 AI 厂商的官方发布页采集资料，给定时任务提供可追溯的实时上下文。
 * 这里故意只维护官方域名，模型不得把自身训练记忆当作“最新资讯”。
 */
@Component
public class AiVendorNewsCollector {
    private static final Logger log = LoggerFactory.getLogger(AiVendorNewsCollector.class);
    /** 单页最大保留字符数，避免多个厂商页面合并后超过模型上下文。 */
    private static final int MAX_PAGE_CHARS = 4_500;
    /** 官方更新入口；页面内容变化由每日任务重新抓取，不依赖搜索引擎排序。 */
    private static final List<OfficialFeed> FEEDS = List.of(
            new OfficialFeed("OpenAI", "https://developers.openai.com/api/docs/models"),
            new OfficialFeed("Anthropic", "https://platform.claude.com/docs/en/release-notes/overview"),
            new OfficialFeed("Google DeepMind", "https://deepmind.google/models/model-cards/"),
            new OfficialFeed("xAI", "https://docs.x.ai/developers/models"),
            new OfficialFeed("字节跳动 Seed", "https://seed.bytedance.com/zh/blog"),
            new OfficialFeed("Moonshot AI", "https://github.com/MoonshotAI"),
            new OfficialFeed("MiniMax 稀宇科技", "https://www.minimaxi.com/news"),
            new OfficialFeed("智谱 Z.ai", "https://docs.z.ai/release-notes/new-released"),
            new OfficialFeed("DeepSeek", "https://api-docs.deepseek.com/updates/"),
            new OfficialFeed("阿里通义千问", "https://qwenlm.github.io/blog/"),
            new OfficialFeed("Meta Llama", "https://github.com/meta-llama/llama-models"));

    private final PythonExtractionClient extractionClient;

    /**
     * 创建官方资讯采集器。
     *
     * @param extractionClient 独立 Python 网页提取服务客户端
     */
    public AiVendorNewsCollector(PythonExtractionClient extractionClient) {
        this.extractionClient = extractionClient;
    }

    /**
     * 逐个抓取官方更新入口；单个厂商失败不会使其他厂商证据丢失。
     *
     * @param fromDate 增量窗口起始日期（含）
     * @param toDate 增量窗口结束日期（含）
     * @return 可直接交给模型的证据包及采集统计
     */
    public CollectionBundle collect(LocalDate fromDate, LocalDate toDate) {
        List<CapturedPage> pages = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (OfficialFeed feed : FEEDS) {
            try {
                PythonExtractionClient.ExtractionResult result = extractionClient.extractWeb(feed.url());
                String markdown = limit(result.markdown(), MAX_PAGE_CHARS);
                pages.add(new CapturedPage(feed.vendor(), feed.url(), markdown));
                log.info("AI vendor official feed captured vendor={} url={} chars={}",
                        feed.vendor(), feed.url(), markdown.length());
            } catch (Exception exception) {
                failures.add(feed.vendor() + "：" + readableMessage(exception));
                log.error("AI vendor official feed capture failed vendor={} url={}",
                        feed.vendor(), feed.url(), exception);
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("所有 AI 厂商官方页面均采集失败，未调用模型，失败详情：" + String.join("；", failures));
        }
        StringBuilder evidence = new StringBuilder();
        evidence.append("资讯时间窗口：").append(fromDate).append(" 至 ").append(toDate).append("\n\n");
        for (CapturedPage page : pages) {
            evidence.append("## ").append(page.vendor()).append("\n")
                    .append("官方来源：").append(page.url()).append("\n\n")
                    .append(page.markdown()).append("\n\n");
        }
        return new CollectionBundle(fromDate, toDate, pages, failures, evidence.toString());
    }

    /** 限制单个厂商页面占用的上下文。 */
    private String limit(String value, int maxChars) {
        String content = value == null ? "" : value.trim();
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "\n[页面内容已截断]";
    }

    /** 将底层异常收敛为任务记录可展示的短消息。 */
    private String readableMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    /** 官方资讯入口定义。 */
    private record OfficialFeed(String vendor, String url) { }
    /** 单个成功采集的官方页面。 */
    public record CapturedPage(String vendor, String url, String markdown) { }
    /** 一轮采集的完整证据包。 */
    public record CollectionBundle(LocalDate fromDate, LocalDate toDate, List<CapturedPage> pages,
                                   List<String> failures, String evidenceMarkdown) { }
}
