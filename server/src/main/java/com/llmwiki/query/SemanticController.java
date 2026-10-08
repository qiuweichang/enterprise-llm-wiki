package com.llmwiki.query;

import com.llmwiki.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 提供空间语义索引运维入口和真实检索测试；所有操作复用 RBAC 与租户隔离。 */
@RestController
@RequestMapping("/api/semantic")
public class SemanticController {
    private final SemanticIndexService index;
    private final EmbeddingClient client;
    private final QueryRetrievalService retrieval;

    /** 依赖分别承担事务、推理与召回，防止网络请求包含在数据库事务中。 */
    public SemanticController(SemanticIndexService index,EmbeddingClient client,QueryRetrievalService retrieval) {
        this.index=index;this.client=client;this.retrieval=retrieval;
    }

    /** 当前空间的进度和模型信息，查询用户也可读取可用状态。 */
    @GetMapping
    @RequiresPermission("QUERY_EXECUTE")
    public Map<String,Object> status() {
        return Map.of("model",EmbeddingClient.MODEL_ID,"dimensions",EmbeddingClient.DIMENSIONS,
                "storage","PostgreSQL real[] / 精确余弦","index",index.snapshot(RequestContext.require()));
    }

    /** 管理员查看索引成功、失败及待处理列表。 */
    @GetMapping("/jobs")
    @RequiresPermission("MODEL_MANAGE")
    public List<Map<String,Object>> jobs() { return index.jobs(RequestContext.require()); }

    /** 启用时先真实推理验证，不允许将不可用的服务保存为已启用。 */
    @PutMapping
    @RequiresPermission("MODEL_MANAGE")
    public Map<String,Object> configure(@Valid @RequestBody Settings settings) {
        if(settings.enabled()) checkedEmbedding("语义检索连接测试");
        index.settings(RequestContext.require(),settings.enabled(),settings.minScore());
        return status();
    }

    /** 一键重新排队所有已发布页面，返回最新进度。 */
    @PostMapping("/rebuild")
    @RequiresPermission("MODEL_MANAGE")
    public Map<String,Object> rebuild() { index.rebuild(RequestContext.require());return status(); }

    /** 测试真实中文向量与当前空间召回；不调用聊天模型，不写知识库。 */
    @PostMapping("/test")
    @RequiresPermission("MODEL_MANAGE")
    public Map<String,Object> test(@Valid @RequestBody TestRequest request) {
        var user=RequestContext.require();long start=System.nanoTime();
        var vector=checkedEmbedding(request.question());
        var pages=retrieval.retrieve(user,request.question(),vector,index.snapshot(user).minScore());
        return Map.of("success",true,"dimensions",vector.size(),"durationMs",(System.nanoTime()-start)/1_000_000,
                "pages",pages.stream().map(p->Map.of("id",p.id(),"title",p.title())).toList());
    }

    /** 管理接口保留可操作的服务失败原因，不把模型错误误报成密钥解密失败或通用 500。 */
    private List<Double> checkedEmbedding(String question) {
        try { return client.embed(List.of(question),true).getFirst(); }
        catch (IllegalStateException error) {
            throw new com.llmwiki.common.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "SEMANTIC_UNAVAILABLE",error.getMessage());
        }
    }

    /** 空间级开关和语义相似度下限，不与生成模型温度等参数混用。 */
    public record Settings(boolean enabled,@DecimalMin("0.0") @DecimalMax("1.0") double minScore) { }
    /** 限制测试长度，防止管理员接口传入无限文本。 */
    public record TestRequest(@NotBlank @Size(max=320) String question) { }
}
