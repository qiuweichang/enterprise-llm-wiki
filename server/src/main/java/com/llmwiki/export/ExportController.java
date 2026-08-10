package com.llmwiki.export;

import com.llmwiki.security.RequiresPermission;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 提供 Obsidian 兼容 Wiki 导出下载。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {
    private final ExportSnapshotService snapshotService;
    private final ObsidianExportService exportService;

    /** 创建导出控制器。 */
    public ExportController(ExportSnapshotService snapshotService, ObsidianExportService exportService) {
        this.snapshotService = snapshotService;
        this.exportService = exportService;
    }

    /**
     * 先在只读事务加载一致快照，再以流式 Zip 响应写出，避免长事务和大内存字节数组。
     */
    @GetMapping("/obsidian")
    @RequiresPermission("EXPORT_WIKI")
    public ResponseEntity<StreamingResponseBody> exportObsidian() {
        ExportSnapshotService.ExportSnapshot snapshot = snapshotService.load();
        StreamingResponseBody body = output -> exportService.write(snapshot, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("llm-wiki-obsidian.zip", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
