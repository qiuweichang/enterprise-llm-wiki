package com.llmwiki.source;

import com.llmwiki.security.RequestContext;
import com.llmwiki.security.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 暴露来源创建、上传和列表 API。
 */
@RestController
@RequestMapping("/api/sources")
public class SourceController {
    private final SourceService sourceService;
    private final LocalObjectStorage objectStorage;

    /** 创建来源控制器。 */
    public SourceController(SourceService sourceService, LocalObjectStorage objectStorage) {
        this.sourceService = sourceService;
        this.objectStorage = objectStorage;
    }

    /** 创建文本或网页来源。 */
    @PostMapping
    @RequiresPermission("SOURCE_CREATE")
    public SourceService.CreateSourceResult create(@Valid @RequestBody SourceService.CreateSourceRequest request) {
        return sourceService.create(request);
    }

    /**
     * 流式保存文件后登记来源；对象写入失败不会产生数据库记录。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("SOURCE_CREATE")
    public SourceService.CreateSourceResult upload(@RequestPart("file") MultipartFile file,
                                                   @RequestParam String title,
                                                   @RequestParam(defaultValue = "FILE") String sourceType) {
        var user = RequestContext.require();
        LocalObjectStorage.StoredObject object = objectStorage.store(user.organizationId(), user.workspaceId(), file);
        return sourceService.createUploaded(title, sourceType, object);
    }

    /** 列出当前空间来源。 */
    @GetMapping
    @RequiresPermission("SOURCE_READ")
    public List<SourceService.SourceSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return sourceService.list(limit);
    }

    /** 下载当前租户有权访问的原始文件，响应不暴露服务器绝对路径。 */
    @GetMapping("/{sourceId}/download")
    @RequiresPermission("SOURCE_READ")
    public ResponseEntity<Resource> download(@PathVariable java.util.UUID sourceId) {
        SourceService.DownloadInfo info = sourceService.download(sourceId, objectStorage);
        String encodedFilename = URLEncoder.encode(info.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(info.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .body(new FileSystemResource(info.path()));
    }

    /** 重新提取并编译已有来源。 */
    @PostMapping("/{sourceId}/refresh")
    @RequiresPermission("SOURCE_CREATE")
    public SourceService.CreateSourceResult refresh(@PathVariable java.util.UUID sourceId) {
        return sourceService.refresh(sourceId);
    }
}
