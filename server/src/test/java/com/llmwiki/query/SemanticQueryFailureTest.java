package com.llmwiki.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 查询编排故障测试；真实向量正确性由 Python 与数据库集成测试覆盖。 */
class SemanticQueryFailureTest {
    /** Python 故障时必须明确标注降级且不缓存，下一次调用立即重试，不把故障伪装成混合检索。 */
    @Test void failedEmbeddingIsVisibleAndNotCached() {
        var retrieval=mock(QueryRetrievalService.class);
        var composer=mock(AnswerComposer.class);
        var cache=mock(WikiCacheService.class);
        var index=mock(SemanticIndexService.class);
        var embeddings=mock(EmbeddingClient.class);
        var user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,"","",0,Set.of());
        when(index.snapshot(user)).thenReturn(new SemanticIndexService.Snapshot(true,0.5,"index-v1",1,1,0));
        when(cache.get(anyString(),any())).thenReturn(Optional.empty());
        when(embeddings.embed(anyList(),eq(true))).thenThrow(new IllegalStateException("语义服务连接失败或超时"));
        when(retrieval.retrieve(eq(user),anyString(),anyList(),anyDouble())).thenReturn(List.of());
        when(composer.compose(any(),any(),anyString(),anyString(),anyList(),eq(false)))
                .thenReturn(new AnswerComposer.ComposedAnswer("没有知识","NO_KNOWLEDGE","",""));
        var query=new QueryService(retrieval,composer,cache,new ObjectMapper(),index,embeddings);
        for(int i=0;i<2;i++) {
            var response=query.queryForUser(user,"问题","问题","",false);
            assertThat(response.retrievalMode()).isEqualTo("DEGRADED");
            assertThat(response.retrievalMessage()).contains("连接失败");
        }
        verify(embeddings,times(2)).embed(anyList(),eq(true));
        verify(cache,never()).put(anyString(),any(),any());
    }
}
