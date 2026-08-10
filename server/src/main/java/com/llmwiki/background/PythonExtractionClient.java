package com.llmwiki.background;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.config.LlmWikiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 独立 Python 提取服务客户端，将网页、OCR 和音视频转写隔离在 Java 主进程之外。
 */
@Component
public class PythonExtractionClient {
    /** 记录跨进程提取请求的开始与完成，便于定位队列任务卡点。 */
    private static final Logger log = LoggerFactory.getLogger(PythonExtractionClient.class);
    /**
     * 直接发送固定字节请求体的 JDK HTTP 客户端。
     * 强制 HTTP/1.1 是因为当前 uvicorn 组合在 JDK 默认协议协商下会把 POST 正文识别为空。
     */
    private final HttpClient httpClient;
    /** Python 服务根地址，用于解析各提取端点。 */
    private final URI baseUri;
    /** 单次提取调用的整体超时，防止后台虚拟线程无限等待。 */
    private final Duration requestTimeout;
    /** 用于从 FastAPI 错误载荷中提取可读原因。 */
    private final ObjectMapper objectMapper;

    /**
     * 按配置创建带连接和读取超时的 HTTP 客户端。
     *
     * @param properties 应用配置
     * @param objectMapper JSON 错误解析器
     */
    public PythonExtractionClient(LlmWikiProperties properties, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUri = URI.create(properties.python().baseUrl());
        this.requestTimeout = properties.python().timeout();
        this.objectMapper = objectMapper;
    }

    /**
     * 提取网页正文为 Markdown。
     *
     * @param url 网页 URL
     * @return 提取结果
     */
    public ExtractionResult extractWeb(String url) {
        return postJson("/extract/web", Map.of("url", url));
    }

    /**
     * 提取普通文档或图片，图片和扫描 PDF 由 Python OCR 降级链处理。
     *
     * @param absolutePath 共享对象存储中的绝对路径
     * @param contentType MIME 类型
     * @return 提取结果
     */
    public ExtractionResult extractFile(String absolutePath, String contentType) {
        return postJson("/extract/file", Map.of("path", absolutePath, "content_type", contentType));
    }

    /**
     * 转写音频或视频。
     *
     * @param absolutePath 共享对象存储中的绝对路径
     * @return 带时间戳文本的转写结果
     */
    public ExtractionResult transcribe(String absolutePath) {
        return postJson("/transcribe", Map.of("path", absolutePath));
    }

    /**
     * 以明确的 JSON Content-Type 调用 FastAPI，避免请求体被解释成空内容。
     *
     * @param uri Python 服务路径
     * @param body JSON 请求对象
     * @return 非空提取结果
     */
    private ExtractionResult postJson(String uri, Map<String, ?> body) {
        byte[] payload = serializeRequest(body);
        long startedAt = System.nanoTime();
        log.info("Python extraction request started uri={} payloadBytes={}", uri, payload.length);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(uri))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("内容提取失败（Python " + response.statusCode() + "）：" +
                        errorDetail(new String(response.body(), StandardCharsets.UTF_8)));
            }
            ExtractionResult result = requireResult(objectMapper.readValue(response.body(), ExtractionResult.class));
            log.info("Python extraction request completed uri={} durationMs={}", uri,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Python 提取请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Python 提取服务通信失败", exception);
        }
    }

    /**
     * 显式生成 UTF-8 JSON 字节，避免不同 IDEA/Spring 消息转换器组合把 Map 请求体发送为空。
     *
     * @param body 待发送的请求字段
     * @return 可直接写入 HTTP 请求体的 JSON 字节
     */
    private byte[] serializeRequest(Map<String, ?> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Python 提取请求", exception);
        }
    }

    /** 从 FastAPI 的字符串或校验错误数组中提取首条完整错误。 */
    private String errorDetail(String body) {
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            if (detail.isTextual()) {
                return detail.asText();
            }
            if (detail.isArray() && !detail.isEmpty()) {
                JsonNode first = detail.get(0);
                String location = first.path("loc").isArray() ? first.path("loc").toString() : "";
                return first.path("msg").asText("请求参数无效") + (location.isBlank() ? "" : " " + location);
            }
        } catch (Exception ignored) {
            // 非标准响应保留经过长度限制的原始文本，方便管理员定位代理或网关错误。
        }
        String normalized = body == null ? "未知错误" : body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "…";
    }

    /** 保证远端返回了可用正文。 */
    private ExtractionResult requireResult(ExtractionResult result) {
        if (result == null || result.markdown() == null || result.markdown().isBlank()) {
            throw new IllegalStateException("Python extraction service returned empty content");
        }
        return result;
    }

    /** Python 提取服务统一返回结构。 */
    public record ExtractionResult(String title, String markdown, String contentHash, Map<String, Object> metadata) { }
}
