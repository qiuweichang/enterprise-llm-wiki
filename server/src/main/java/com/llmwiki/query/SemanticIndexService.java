package com.llmwiki.query;

import com.llmwiki.security.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 持久化向量索引队列、租户配置和原子提交；网络推理始终在事务之外执行。 */
@Service
public class SemanticIndexService {
    private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenant;
    private final TransactionTemplate tx;

    /** 使用显式短事务，便于后台任务在任意线程安全建立租户上下文。 */
    public SemanticIndexService(JdbcClient jdbc, TenantDatabaseContext tenant, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tenant = tenant; this.tx = new TransactionTemplate(manager);
    }

    /** 读取数据库快照指纹：页面发布、索引完成、重建和开关均使 Query 缓存键改变。 */
    public Snapshot snapshot(AuthenticatedUser user) {
        return tx.execute(status -> {
            tenant.apply(user.organizationId(), user.workspaceId());
            var setting = jdbc.sql("select enabled,min_score from semantic_settings").query()
                    .listOfRows().stream().findFirst().orElse(Map.of("enabled", true, "min_score", 0.50));
            var row = jdbc.sql("""
                    select md5(coalesce(string_agg(p.id::text || ':' || coalesce(p.current_revision_id::text,'') || ':' ||
                        p.title || ':' || p.content_hash || ':' || p.status || ':' || coalesce(j.generation::text,'') || ':' ||
                        coalesce(j.updated_at::text,''), '|' order by p.id),'')) as fingerprint,
                        count(*) filter(where p.status='PUBLISHED') as total,
                        count(*) filter(where p.status='PUBLISHED' and j.status='DONE') as ready,
                        count(*) filter(where p.status='PUBLISHED' and j.status='FAILED') as failed
                    from wiki_pages p left join semantic_jobs j on j.page_id=p.id
                    """).query().singleRow();
            return new Snapshot((Boolean) setting.get("enabled"), ((Number) setting.get("min_score")).doubleValue(),
                    row.get("fingerprint").toString(), ((Number) row.get("total")).longValue(),
                    ((Number) row.get("ready")).longValue(), ((Number) row.get("failed")).longValue());
        });
    }

    /** 管理端查看最近失败原因和每页处理状态，最多展示最近 50 项。 */
    public List<Map<String,Object>> jobs(AuthenticatedUser user) {
        return tx.execute(status -> {
            tenant.apply(user.organizationId(), user.workspaceId());
            return jdbc.sql("""
                    select j.page_id as "pageId",p.title,j.status,j.attempts,j.last_error as "lastError",
                           j.updated_at as "updatedAt",(select count(*) from semantic_chunks c where c.page_id=j.page_id) as chunks
                    from semantic_jobs j join wiki_pages p on p.id=j.page_id
                    order by j.updated_at desc,j.page_id limit 50
                    """).query().listOfRows();
        });
    }

    /** 保存当前空间开关；启用前由控制器执行真实向量测试。阈值范围由控制器和数据库共同约束。 */
    public void settings(AuthenticatedUser user, boolean enabled, double minScore) {
        tx.executeWithoutResult(status -> {
            tenant.apply(user.organizationId(), user.workspaceId());
            jdbc.sql("""
                    insert into semantic_settings(organization_id,workspace_id,enabled,min_score)
                    values(:org,:ws,:enabled,:score) on conflict(workspace_id) do update
                    set enabled=excluded.enabled,min_score=excluded.min_score,updated_at=clock_timestamp()
                    """).param("org",user.organizationId()).param("ws",user.workspaceId())
                    .param("enabled",enabled).param("score",minScore).update();
            log.info("语义检索配置更新 workspaceId={} enabled={} threshold={}",user.workspaceId(),enabled,minScore);
        });
    }

    /** 重建只使当前空间已发布页面的派生索引失效，不修改知识；旧 worker 的令牌同时失效。 */
    public void rebuild(AuthenticatedUser user) {
        tx.executeWithoutResult(status -> {
            tenant.apply(user.organizationId(), user.workspaceId());
            jdbc.sql("""
                    insert into semantic_jobs(page_id,organization_id,workspace_id)
                    select id,organization_id,workspace_id from wiki_pages where status='PUBLISHED' and current_revision_id is not null
                    on conflict(page_id) do update set generation=semantic_jobs.generation+1,status='PENDING',
                    attempts=0,lease_token=null,lease_until=null,next_run_at=now(),last_error='',updated_at=clock_timestamp()
                    """).update();
            log.info("语义索引重建已排队 workspaceId={}",user.workspaceId());
        });
    }

    /** 扫描空间元数据；实际页面读取在每个空间的强制 RLS 短事务内执行。 */
    public Optional<Lease> claim() {
        for (var space : jdbc.sql("select organization_id,id from workspaces order by id").query().listOfRows()) {
            UUID org = (UUID)space.get("organization_id"), ws = (UUID)space.get("id");
            var result = tx.execute(status -> {
                tenant.apply(org,ws);
                jdbc.sql("update semantic_jobs set status='FAILED',last_error='索引任务多次超时，请检查 Python 服务后重建',updated_at=clock_timestamp() where status='RUNNING' and lease_until<now() and attempts>=5").update();
                return jdbc.sql("""
                        with candidate as (
                          select j.page_id from semantic_jobs j join wiki_pages p on p.id=j.page_id
                          where p.status='PUBLISHED' and p.current_revision_id is not null
                            and coalesce((select enabled from semantic_settings),true)
                            and ((j.status='PENDING' and j.next_run_at<=now()) or (j.status='RUNNING' and j.lease_until<now()))
                          order by j.next_run_at,j.page_id for update of j skip locked limit 1
                        )
                        update semantic_jobs j set status='RUNNING',attempts=attempts+1,lease_token=gen_random_uuid(),
                          lease_until=now()+interval '5 minutes',updated_at=clock_timestamp()
                        from candidate c,wiki_pages p where j.page_id=c.page_id and p.id=j.page_id
                        returning j.page_id,j.generation,j.lease_token,p.current_revision_id,p.title,p.content_markdown
                        """).query((rs,n) -> new Lease(org,ws,(UUID)rs.getObject("page_id"),rs.getLong("generation"),
                        (UUID)rs.getObject("lease_token"),(UUID)rs.getObject("current_revision_id"),rs.getString("title"),rs.getString("content_markdown"))).optional();
            });
            if (result != null && result.isPresent()) return result;
        }
        return Optional.empty();
    }

    /** 每批推理前延长有效租约；过期或被重建替换的任务不能复活，立即停止后续计算。 */
    public boolean renew(Lease lease) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            tenant.apply(lease.org(),lease.workspace());
            return jdbc.sql("""
                    update semantic_jobs set lease_until=now()+interval '5 minutes'
                    where page_id=:id and generation=:generation and lease_token=:token and status='RUNNING' and lease_until>now()
                    """).param("id",lease.page()).param("generation",lease.generation()).param("token",lease.token()).update()==1;
        }));
    }

    /** 发布全部块和 DONE 状态同事务完成；锁顺序页面→任务，与页面发布触发器一致，避免死锁和旧任务覆盖。 */
    public boolean complete(Lease lease, List<String> chunks, List<List<Double>> vectors) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            tenant.apply(lease.org(),lease.workspace());
            var page = jdbc.sql("select current_revision_id,status from wiki_pages where id=:id for update")
                    .param("id",lease.page()).query().listOfRows().stream().findFirst();
            if (page.isEmpty() || !lease.revision().equals(page.get().get("current_revision_id")) || !"PUBLISHED".equals(page.get().get("status"))) return false;
            int owned = jdbc.sql("""
                    update semantic_jobs set status='DONE',last_error='',lease_until=null,updated_at=clock_timestamp()
                    where page_id=:id and generation=:generation and lease_token=:token and status='RUNNING'
                      and lease_until>now()
                    """).param("id",lease.page()).param("generation",lease.generation()).param("token",lease.token()).update();
            if (owned != 1) return false;
            if (chunks.isEmpty() || chunks.size()!=vectors.size()) throw new IllegalStateException("索引块与向量数不一致");
            jdbc.sql("delete from semantic_chunks where page_id=:id").param("id",lease.page()).update();
            for (int i=0;i<chunks.size();i++) jdbc.sql("""
                    insert into semantic_chunks(organization_id,workspace_id,page_id,revision_id,generation,model_id,ordinal,chunk_text,embedding)
                    values(:org,:ws,:page,:revision,:generation,:model,:ordinal,:text,cast(:vector as real[]))
                    """).param("org",lease.org()).param("ws",lease.workspace()).param("page",lease.page())
                    .param("revision",lease.revision()).param("generation",lease.generation()).param("model",EmbeddingClient.MODEL_ID)
                    .param("ordinal",i).param("text",chunks.get(i)).param("vector",EmbeddingClient.array(vectors.get(i))).update();
            log.info("语义索引完成 pageId={} generation={} chunks={}",lease.page(),lease.generation(),chunks.size());
            return true;
        }));
    }

    /** 失败按指数退避重试，五次后保留 FAILED 供管理员重建；已失去租约的任务不得回写状态。 */
    public void fail(Lease lease, Exception error) {
        tx.executeWithoutResult(status -> {
            tenant.apply(lease.org(),lease.workspace());
            String message=Objects.toString(error.getMessage(),"语义索引异常");
            jdbc.sql("""
                    update semantic_jobs set status=case when attempts>=5 then 'FAILED' else 'PENDING' end,
                    next_run_at=now()+make_interval(secs=>60*power(2,least(attempts,5))::int),lease_until=null,
                    last_error=:error,updated_at=clock_timestamp()
                    where page_id=:id and generation=:generation and lease_token=:token and status='RUNNING'
                    """).param("id",lease.page()).param("generation",lease.generation()).param("token",lease.token())
                    .param("error",message.substring(0,Math.min(message.length(),500))).update();
        });
    }

    /** 可序列化的空间索引快照；fingerprint 用于缓存版本而不是安全凭据。 */
    public record Snapshot(boolean enabled,double minScore,String fingerprint,long total,long ready,long failed) { }
    /** 每次领取生成唯一令牌，generation 防重建覆盖，revision 防发布版本覆盖。 */
    public record Lease(UUID org,UUID workspace,UUID page,long generation,UUID token,UUID revision,String title,String markdown) { }
}
