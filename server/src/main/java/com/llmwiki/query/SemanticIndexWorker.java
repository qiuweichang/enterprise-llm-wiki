package com.llmwiki.query;

import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 有界单任务索引 worker；调度线程只触发，CPU/HTTP 推理不占数据库连接或公共调度线程。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="llm-wiki.semantic.worker-enabled",havingValue="true",matchIfMissing=true)
public class SemanticIndexWorker {
    private static final Logger log=LoggerFactory.getLogger(SemanticIndexWorker.class);
    private final SemanticIndexService index;
    private final EmbeddingClient client;
    /** 一个进程最多一页在推理；多实例依赖数据库租约协调。 */
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("semantic-index-").factory());

    /** 绑定队列事务服务和无事务网络客户端。 */
    public SemanticIndexWorker(SemanticIndexService index,EmbeddingClient client) { this.index=index;this.client=client; }

    /** 每秒检查一次；后台服务重启后，过期租约可被重新领取。 */
    @Scheduled(fixedDelay=1000,initialDelay=10000)
    public void poll() {
        if (!busy.compareAndSet(false,true)) return;
        executor.submit(() -> {
            try { index.claim().ifPresent(this::process); }
            catch(Exception error) { log.error("语义索引调度失败",error); }
            finally { busy.set(false); }
        });
    }

    /** 全文分块按批向量化，完整成功后才原子替换索引，不发布半份文档向量。 */
    private void process(SemanticIndexService.Lease lease) {
        try {
            var chunks=EmbeddingClient.chunks(lease.title(),lease.markdown());
            List<List<Double>> vectors=new ArrayList<>();
            for(int i=0;i<chunks.size();i+=16) {
                if(!index.renew(lease)) return;
                vectors.addAll(client.embed(chunks.subList(i,Math.min(i+16,chunks.size())),false));
            }
            if(!index.complete(lease,chunks,vectors)) log.info("语义索引结果过期，放弃提交 pageId={}",lease.page());
        } catch(Exception error) {
            log.error("语义索引失败 pageId={}",lease.page(),error);
            index.fail(lease,error);
        }
    }

    /** 停机中断未完成推理；未提交任务留给下次启动通过租约恢复。 */
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
