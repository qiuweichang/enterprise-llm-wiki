package com.llmwiki.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证内容寻址和 Unicode 页面标识的稳定性。
 */
class ContentUtilitiesTest {

    /** 相同 UTF-8 内容必须生成稳定 SHA-256。 */
    @Test
    void shouldHashContentDeterministically() {
        String first = ContentHash.sha256("LLM Wiki");
        assertThat(first).hasSize(64).isEqualTo(ContentHash.sha256("LLM Wiki"));
        assertThat(first).isNotEqualTo(ContentHash.sha256("LLM Wiki!"));
    }

    /** 中文应保留，空白和标点应规范为连字符。 */
    @Test
    void shouldCreateUnicodeSlug() {
        assertThat(Slugifier.slugify(" 企业 Wiki：审核流程 ")).isEqualTo("企业-wiki-审核流程");
    }
}
