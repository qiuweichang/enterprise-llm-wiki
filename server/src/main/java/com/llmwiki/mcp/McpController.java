package com.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.common.ApiException;
import com.llmwiki.query.QueryService;
import com.llmwiki.review.ReviewService;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.RequiresPermission;
import com.llmwiki.source.SourceService;
import com.llmwiki.wiki.PageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实现 MCP Streamable HTTP 的 JSON-RPC POST 子集，提供查询、页面、来源和审核工具。
 * 端点接受短期 JWT 或以 lwk_ 开头的可撤销 API Key，并继承同一租户/RBAC 约束。
 */
@RestController
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private final ObjectMapper objectMapper;
    private final QueryService queryService;
    private final PageService pageService;
    private final SourceService sourceService;
    private final ReviewService reviewService;

    /** 创建 MCP 控制器。 */
    public McpController(ObjectMapper objectMapper, QueryService queryService, PageService pageService,
                         SourceService sourceService, ReviewService reviewService) {
        this.objectMapper = objectMapper;
        this.queryService = queryService;
        this.pageService = pageService;
        this.sourceService = sourceService;
        this.reviewService = reviewService;
    }

    /**
     * 处理 MCP JSON-RPC 请求；通知没有 id 时返回 202 且无 JSON-RPC 响应体。
     *
     * @param request JSON-RPC 消息
     * @return MCP 响应
     */
    @PostMapping(value = "/mcp", consumes = "application/json", produces = "application/json")
    @RequiresPermission("MCP_USE")
    public ResponseEntity<Object> handle(@RequestBody JsonNode request) {
        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        if (id == null && method.startsWith("notifications/")) {
            return ResponseEntity.accepted().header("MCP-Protocol-Version", PROTOCOL_VERSION).build();
        }
        try {
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(request.path("params"));
                default -> throw new McpError(-32601, "Method not found: " + method);
            };
            return ResponseEntity.ok().header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(success(id, result));
        } catch (McpError error) {
            return ResponseEntity.ok().header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(id, error.code(), error.getMessage()));
        } catch (ApiException error) {
            return ResponseEntity.ok().header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(id, -32000, error.getMessage()));
        } catch (Exception exception) {
            log.error("MCP request failed method={}", method, exception);
            return ResponseEntity.ok().header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(id, -32603, "Internal tool error"));
        }
    }

    /** 返回 MCP 协商能力。 */
    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "llm-wiki-enterprise", "version", "1.0.0"),
                "instructions", "Query and maintain the current enterprise wiki. Existing-page updates always require human review."
        );
    }

    /** 返回当前版本支持的工具定义。 */
    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("llm_wiki_query", "Ask the compiled wiki and receive revision-level citations.",
                objectSchema(Map.of("question", stringSchema("Question to answer")), List.of("question"))));
        tools.add(tool("llm_wiki_list_pages", "List published pages in the current workspace.",
                objectSchema(Map.of("limit", integerSchema("Maximum pages, 1-100")), List.of())));
        tools.add(tool("llm_wiki_propose_page", "Create a page or propose an update. Existing pages always enter review.",
                objectSchema(Map.of(
                        "pageId", stringSchema("Existing page UUID; omit for a new page"),
                        "title", stringSchema("Page title"),
                        "slug", stringSchema("Optional page slug"),
                        "pageType", stringSchema("TOPIC, ENTITY, SYNTHESIS, README, CONTEXT or QUERY"),
                        "contentMarkdown", stringSchema("Complete proposed Markdown")
                ), List.of("title", "contentMarkdown"))));
        tools.add(tool("llm_wiki_add_text_source", "Add immutable text source and enqueue AI compilation.",
                objectSchema(Map.of("title", stringSchema("Source title"), "text", stringSchema("Source text")),
                        List.of("title", "text"))));
        tools.add(tool("llm_wiki_review_change", "Approve or reject a pending change set.",
                objectSchema(Map.of(
                        "changeSetId", stringSchema("Change set UUID"),
                        "decision", stringSchema("APPROVE or REJECT"),
                        "comment", stringSchema("Review comment; required when rejecting")
                ), List.of("changeSetId", "decision"))));
        return tools;
    }

    /** 执行单个 MCP 工具并返回文本与结构化结果。 */
    private Map<String, Object> callTool(JsonNode params) throws Exception {
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        Object result = switch (name) {
            case "llm_wiki_query" -> {
                requirePermission("QUERY_EXECUTE");
                yield queryService.query(requiredText(arguments, "question"));
            }
            case "llm_wiki_list_pages" -> {
                requirePermission("PAGE_READ");
                int limit = Math.max(1, Math.min(arguments.path("limit").asInt(50), 100));
                yield pageService.list(null, null, limit);
            }
            case "llm_wiki_propose_page" -> {
                requirePermission("PAGE_CREATE");
                UUID pageId = arguments.path("pageId").asText().isBlank() ? null :
                        UUID.fromString(arguments.path("pageId").asText());
                yield pageService.createOrPropose(new PageService.PageMutationRequest(pageId,
                        arguments.path("slug").asText(null), requiredText(arguments, "title"),
                        arguments.path("pageType").asText("TOPIC"), requiredText(arguments, "contentMarkdown")));
            }
            case "llm_wiki_add_text_source" -> {
                requirePermission("SOURCE_CREATE");
                yield sourceService.create(new SourceService.CreateSourceRequest("TEXT",
                        requiredText(arguments, "title"), null, requiredText(arguments, "text")));
            }
            case "llm_wiki_review_change" -> {
                requirePermission("REVIEW_APPROVE");
                UUID changeSetId = UUID.fromString(requiredText(arguments, "changeSetId"));
                String decision = requiredText(arguments, "decision").toUpperCase();
                String comment = arguments.path("comment").asText("");
                if ("APPROVE".equals(decision)) {
                    yield reviewService.approve(changeSetId, comment);
                }
                if ("REJECT".equals(decision)) {
                    yield reviewService.reject(changeSetId, comment);
                }
                throw new McpError(-32602, "decision must be APPROVE or REJECT");
            }
            default -> throw new McpError(-32602, "Unknown tool: " + name);
        };
        JsonNode structured = objectMapper.valueToTree(result);
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", objectMapper.writeValueAsString(result))),
                "structuredContent", structured,
                "isError", false
        );
    }

    /** 在服务直调前执行工具级权限检查，防止绕过 MVC 方法注解。 */
    private void requirePermission(String permission) {
        if (!RequestContext.require().hasPermission(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "MCP 工具缺少权限：" + permission);
        }
    }

    /** 读取必填字符串参数。 */
    private String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText();
        if (value.isBlank()) {
            throw new McpError(-32602, "Missing required argument: " + field);
        }
        return value;
    }

    /** 构造工具描述。 */
    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    /** 构造对象 JSON Schema。 */
    private Map<String, Object> objectSchema(Map<String, ?> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    /** 构造字符串 JSON Schema。 */
    private Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** 构造整数 JSON Schema。 */
    private Map<String, Object> integerSchema(String description) {
        return Map.of("type", "integer", "description", description, "minimum", 1, "maximum", 100);
    }

    /** 构造成功 JSON-RPC 响应，允许 id 为数字、字符串或 null。 */
    private Map<String, Object> success(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id == null || id.isNull() ? null : objectMapper.convertValue(id, Object.class));
        response.put("result", result);
        return response;
    }

    /** 构造 JSON-RPC 错误响应。 */
    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id == null || id.isNull() ? null : objectMapper.convertValue(id, Object.class));
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    /** MCP 协议级错误。 */
    private static class McpError extends RuntimeException {
        private final int code;

        /** 创建 MCP 错误。 */
        McpError(int code, String message) {
            super(message);
            this.code = code;
        }

        /** @return JSON-RPC 错误码 */
        int code() {
            return code;
        }
    }
}
