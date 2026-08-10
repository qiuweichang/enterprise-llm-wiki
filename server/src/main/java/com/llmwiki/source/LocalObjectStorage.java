package com.llmwiki.source;

import com.llmwiki.common.ApiException;
import com.llmwiki.config.LlmWikiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 原始素材对象存储的本地实现。
 * 路径由租户和内容哈希构成，数据库只保存对象键，后续可无缝替换为 S3/MinIO 实现。
 */
@Component
public class LocalObjectStorage {
    private final Path root;

    /**
     * 创建本地对象存储并规范化根目录。
     *
     * @param properties 应用存储配置
     */
    public LocalObjectStorage(LlmWikiProperties properties) {
        this.root = Path.of(properties.storage().root()).toAbsolutePath().normalize();
    }

    /**
     * 流式保存上传文件并计算内容哈希，避免将大文件整体载入内存。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @param file 上传文件
     * @return 对象键、绝对路径、哈希和大小
     */
    public StoredObject store(UUID organizationId, UUID workspaceId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "上传文件不能为空");
        }
        String safeName = sanitizeFilename(file.getOriginalFilename());
        Path temporary = root.resolve("tmp").resolve(UUID.randomUUID().toString()).normalize();
        requireInsideRoot(temporary);
        try {
            Files.createDirectories(temporary.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            String key = organizationId + "/" + workspaceId + "/raw/" + hash + "/" + safeName;
            Path target = root.resolve(key).normalize();
            requireInsideRoot(target);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.deleteIfExists(temporary);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return new StoredObject(key.replace('\\', '/'), target.toString(), safeName, hash, Files.size(target),
                    file.getContentType());
        } catch (IOException | NoSuchAlgorithmException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 主异常更能说明失败原因，临时文件会由运维清理任务回收。
            }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OBJECT_STORAGE_FAILED", "原始文件保存失败");
        }
    }

    /**
     * 将数据库对象键解析为受根目录约束的绝对路径，供独立 Python 服务读取。
     *
     * @param objectKey 数据库对象键
     * @return 规范化绝对路径
     */
    public Path resolve(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        requireInsideRoot(path);
        return path;
    }

    /** 清理文件名中的目录和控制字符，阻止路径穿越。 */
    private String sanitizeFilename(String filename) {
        String base = filename == null ? "upload.bin" : Path.of(filename).getFileName().toString();
        String safe = base.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]+", "-").trim();
        return safe.isBlank() ? "upload.bin" : safe;
    }

    /** 确认解析结果始终位于配置根目录内。 */
    private void requireInsideRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OBJECT_KEY", "非法对象路径");
        }
    }

    /** 已保存对象的元数据。 */
    public record StoredObject(String objectKey, String absolutePath, String originalFilename,
                               String contentHash, long size, String contentType) { }
}
