package com.llmwiki.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 提供内容寻址使用的 SHA-256 哈希，统一来源去重、版本校验和审核合并语义。
 */
public final class ContentHash {
    private ContentHash() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256。
     *
     * @param content 待计算内容，null 按空字符串处理
     * @return 小写十六进制哈希
     */
    public static String sha256(String content) {
        try {
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

