package com.llmwiki.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 周期性领取有限数量任务并交给虚拟线程处理。
 * 领取和完成分别是短事务，Python/LLM 网络等待不会占用数据库锁或连接。
 */
@Component
public class IngestionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(IngestionCoordinator.class);
    private static final int CLAIMS_PER_TICK = 4;
    private final IngestionJobService jobService;
    private final PythonExtractionClient extractionClient;
    private final KnowledgeCompiler compiler;
    private final ExecutorService ingestionExecutor;

    /** 创建摄取协调器。 */
    public IngestionCoordinator(IngestionJobService jobService, PythonExtractionClient extractionClient,
                                KnowledgeCompiler compiler, ExecutorService ingestionExecutor) {
        this.jobService = jobService;
        this.extractionClient = extractionClient;
        this.compiler = compiler;
        this.ingestionExecutor = ingestionExecutor;
    }

    /**
     * 每次最多领取四项任务，多实例依靠 SKIP LOCKED 和租约自然分片。
     */
    @Scheduled(fixedDelayString = "${llm-wiki.worker.poll-delay:3s}")
    public void poll() {
        for (int index = 0; index < CLAIMS_PER_TICK; index++) {
            IngestionJobService.JobLease lease = jobService.claim();
            if (lease == null) {
                return;
            }
            ingestionExecutor.submit(() -> process(lease));
        }
    }

    /** 在虚拟线程中执行提取、编译和事务化完成。 */
    private void process(IngestionJobService.JobLease lease) {
        try {
            IngestionJobService.JobMaterial material = jobService.loadMaterial(lease);
            PythonExtractionClient.ExtractionResult extraction = extract(material);
            KnowledgeCompiler.CompilationResult compiled = compiler.compile(lease.organizationId(), lease.workspaceId(),
                    material.source().title(), extraction.markdown(), material.currentPage());
            jobService.complete(material, extraction, compiled);
        } catch (Exception exception) {
            jobService.fail(lease, exception);
        }
    }

    /** 根据来源类型分流到直接复用、网页、文件 OCR 或音视频转写接口。 */
    private PythonExtractionClient.ExtractionResult extract(IngestionJobService.JobMaterial material) {
        IngestionJobService.SourceRow source = material.source();
        if (Boolean.TRUE.equals(material.payload().get("reuseLatest")) && source.extractedMarkdown() != null) {
            return new PythonExtractionClient.ExtractionResult(source.title(), source.extractedMarkdown(),
                    source.contentHash(), Map.of("extractor", "stored-source-version"));
        }
        return switch (source.sourceType()) {
            case "TEXT" -> {
                if (source.extractedMarkdown() == null || source.extractedMarkdown().isBlank()) {
                    throw new IllegalStateException("TEXT 来源没有可编译的 Markdown 内容");
                }
                yield new PythonExtractionClient.ExtractionResult(source.title(), source.extractedMarkdown(),
                        source.contentHash(), Map.of("extractor", "stored-source-version"));
            }
            case "URL" -> extractionClient.extractWeb(source.canonicalUri());
            case "AUDIO", "VIDEO" -> extractionClient.transcribe(jobService.resolveObjectPath(material));
            case "FILE" -> extractionClient.extractFile(jobService.resolveObjectPath(material),
                    String.valueOf(material.payload().getOrDefault("contentType", "application/octet-stream")));
            default -> throw new IllegalStateException("Unsupported background source type: " + source.sourceType());
        };
    }
}
