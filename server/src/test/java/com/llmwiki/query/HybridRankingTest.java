package com.llmwiki.query;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 纯算法回归：融合不依赖分数尺度，Unicode 分块完整覆盖尾部。 */
class HybridRankingTest {
    /** 两路共同命中应排到前面，即使某一路原始分值极小。 */
    @Test void reciprocalRankFusionDeduplicates() {
        var a=page(1,9999);var b=page(2,0.01);var c=page(3,0.8);
        assertThat(QueryRetrievalService.fuse(List.of(a,b),List.of(c,b))).extracting(QueryRetrievalService.RetrievedPage::id)
                .containsExactly(b.id(),a.id(),c.id());
        assertThat(QueryRetrievalService.fuse(List.of(),List.of())).isEmpty();
    }

    /** 不允许对整份长文档只取前几个块；中文、代理对字符和最后一块都必须保留。 */
    @Test void chunksPreserveTailAndUnicode() {
        String body="知识🌍".repeat(3000)+"尾部唯一证据";
        var chunks=EmbeddingClient.chunks("文档",body);
        assertThat(chunks).hasSizeGreaterThan(20);
        assertThat(chunks.getLast()).contains("尾部唯一证据");
        assertThat(chunks).allSatisfy(c->assertThat(c.codePointCount(0,c.length())).isLessThanOrEqualTo(385));
    }

    /** 构造固定 ID 的轻量页面，便于稳定验证同分排序。 */
    private QueryRetrievalService.RetrievedPage page(int id,double score) {
        return new QueryRetrievalService.RetrievedPage(new UUID(0,id),"p"+id,"页面"+id,"TOPIC",1,"内容",score);
    }
}
