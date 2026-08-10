package com.llmwiki.export;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将数据库事实快照投影为通用 Markdown/Obsidian Vault。
 * 导出是单向生成视图，不与数据库形成双主源。
 */
@Service
public class ObsidianExportService {

    /**
     * 将快照写为 Zip 流，包含 Markdown frontmatter、WikiLink、索引、审计和最小 Obsidian 配置。
     *
     * @param snapshot 一致数据库快照
     * @param output 目标输出流，调用方负责生命周期
     */
    public void write(ExportSnapshotService.ExportSnapshot snapshot, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (ExportSnapshotService.ExportPage page : snapshot.pages()) {
                writeEntry(zip, pathFor(page), renderPage(page));
            }
            writeEntry(zip, "_meta/index.md", renderIndex(snapshot));
            writeEntry(zip, "_meta/audit.md", renderAudit(snapshot));
            writeEntry(zip, ".obsidian/app.json", "{\"showLineNumber\":true,\"strictLineBreaks\":false}\n");
            writeEntry(zip, ".obsidian/graph.json", "{\"collapse-filter\":false,\"showTags\":true,\"showAttachmentsOnly\":false}\n");
        }
    }

    /** 为页面生成 Obsidian 兼容 frontmatter 和正文。 */
    private String renderPage(ExportSnapshotService.ExportPage page) {
        StringBuilder markdown = new StringBuilder("---\n")
                .append("id: \"").append(page.id()).append("\"\n")
                .append("title: ").append(yaml(page.title())).append("\n")
                .append("aliases:\n  - ").append(yaml(page.slug())).append("\n")
                .append("type: ").append(page.pageType().toLowerCase(Locale.ROOT)).append("\n")
                .append("revision: ").append(page.revisionNo()).append("\n")
                .append("updated: \"").append(page.updatedAt()).append("\"\n")
                .append("sources:\n");
        if (page.sources().isEmpty()) {
            markdown.append("  []\n");
        } else {
            page.sources().forEach(source -> markdown.append("  - ").append(yaml(source)).append("\n"));
        }
        markdown.append("---\n\n").append(page.contentMarkdown().trim()).append("\n");
        return markdown.toString();
    }

    /** 生成 Vault 总索引。 */
    private String renderIndex(ExportSnapshotService.ExportSnapshot snapshot) {
        StringBuilder index = new StringBuilder("# ").append(snapshot.workspaceName()).append("\n\n")
                .append("> Exported from LLM Wiki at ").append(snapshot.exportedAt()).append(".\n\n")
                .append("## Pages\n\n");
        snapshot.pages().forEach(page -> index.append("- [[").append(stripUnsafe(page.slug()))
                .append("|").append(page.title()).append("]] · ").append(page.pageType()).append(" · r")
                .append(page.revisionNo()).append("\n"));
        return index.toString();
    }

    /** 生成近期审计日志，便于离线检查导出来源。 */
    private String renderAudit(ExportSnapshotService.ExportSnapshot snapshot) {
        StringBuilder audit = new StringBuilder("# Recent audit trail\n\n");
        snapshot.audit().forEach(line -> audit.append("- ").append(line.createdAt()).append(" · ")
                .append(line.actor()).append(" · `").append(line.action()).append("` · ")
                .append(line.resourceType()).append(" · ").append(line.resourceId()).append("\n"));
        return audit.toString();
    }

    /** 根据页面类型选择稳定目录。 */
    private String pathFor(ExportSnapshotService.ExportPage page) {
        String file = stripUnsafe(page.slug()) + ".md";
        return switch (page.pageType()) {
            case "README" -> "README.md";
            case "CONTEXT" -> "context.md";
            case "ENTITY" -> "entities/" + file;
            case "SYNTHESIS" -> "synthesis/" + file;
            case "QUERY" -> "queries/" + file;
            default -> "topics/" + file;
        };
    }

    /** 写入一个 UTF-8 Zip 条目。 */
    private void writeEntry(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 转义 YAML 双引号文本。 */
    private String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** 清理 Windows 和 Obsidian 都不接受的文件名字符。 */
    private String stripUnsafe(String value) {
        String clean = value.replaceAll("[<>:\"/\\\\|?*#\\[\\]]+", "-").replaceAll("(^[. ]+|[. ]+$)", "");
        return clean.isBlank() ? "untitled" : clean;
    }
}

