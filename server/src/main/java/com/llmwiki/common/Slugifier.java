package com.llmwiki.common;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 生成稳定、可读且可用于 Obsidian 文件名的页面 slug。
 */
public final class Slugifier {
    private Slugifier() {
    }

    /**
     * 将标题转为小写连字符 slug；中文等 Unicode 字母会被保留。
     *
     * @param value 原始标题
     * @return 非空 slug
     */
    public static String slugify(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "untitled" : normalized;
    }
}

